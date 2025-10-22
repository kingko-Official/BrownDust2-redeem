package org.example.mirai.plugin

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.globalEventChannel
import net.mamoe.mirai.message.data.At
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object ConfigData : AutoSavePluginData("ConfigData") {
    var apiUrl: String by value("https://loj2urwaua.execute-api.ap-northeast-1.amazonaws.com/prod/coupon")
    var appId: String by value("bd2-live")
}

object BindingData : AutoSavePluginData("BindingData") {
    val userBindings by value<MutableMap<Long, String>>()
}

object UsedCodeData : AutoSavePluginData("UsedCodeData") {
    val usedCodes by value<MutableMap<Long, MutableList<String>>>()
}

val httpClient = OkHttpClient()

object PluginMain : JavaPlugin(
    JvmPluginDescription(
        id = "com.kingko.bd2redeem",
        name = "BD2redeem",
        version = "1.2.2"
    ) {
        author("KingKo")
        info("兑换码插件")
    }
) {
    private val redeemAliases = listOf("/redeem", "/兑换")

    override fun onEnable() {
        ConfigData.reload()
        BindingData.reload()
        UsedCodeData.reload()
        globalEventChannel().subscribeAlways<MessageEvent> { event ->
            handleCommand(event)
        }
        logger.info("BD2redeem插件已启用！")
    }

    private suspend fun handleCommand(event: MessageEvent) {
        val command = event.message.contentToString().trim()

        when {
            command.startsWith("/绑定 ") -> handleBindCommand(event, command)
            command == "/解绑" -> handleUnbindCommand(event)
            redeemAliases.any { command.startsWith(it) } -> handleRedeemCommand(event, command)
            command.startsWith("/绑定信息") -> handleQueryAllBindings(event)
            command.startsWith("/查询绑定") -> handleQueryBindOther(event)
            command.startsWith("/查询记录") -> handleQueryRecordCommand(event)
        }
    }

    private suspend fun handleBindCommand(event: MessageEvent, command: String) {
        val userId = command.substringAfter("/绑定 ").trim()
        val existingUserid = BindingData.userBindings[event.sender.id]

        if (userId.isEmpty()) {
            event.subject.sendMessage("绑定失败，请提供有效的 userId。")
            return
        }

        // 添加格式验证
        if (userId.length < 2 || userId.contains(" ")) {
            event.subject.sendMessage("userId 格式不正确，请检查后重试。")
            return
        }

        if (existingUserid != null) {
            event.subject.sendMessage("你已绑定：$existingUserid 。请先解绑")
        } else {
            BindingData.userBindings[event.sender.id] = userId
            event.subject.sendMessage("已成功绑定 userId：$userId")
            logger.info("用户 ${event.sender.id} 绑定 userId: $userId")
        }
    }

    private suspend fun handleUnbindCommand(event: MessageEvent) {
        if (BindingData.userBindings.remove(event.sender.id) != null) {
            event.subject.sendMessage("已成功解除绑定。")
            logger.info("用户 ${event.sender.id} 解除绑定")
        } else {
            event.subject.sendMessage("您未绑定任何 userId。")
        }
    }

    private suspend fun handleRedeemCommand(event: MessageEvent, command: String) {
        val args = redeemAliases
            .first { command.startsWith(it) }
            .let { command.substringAfter(it).trim() }
            .split(" ")

        when {
            args.size == 2 -> {
                val userId = args[0]
                val code = args[1]
                sendRedeemRequest(event.subject, event.sender.id, userId, code)
            }
            args.size == 1 -> {
                val code = args[0]
                val userId = BindingData.userBindings[event.sender.id]
                if (userId != null) {
                    sendRedeemRequest(event.subject, event.sender.id, userId, code)
                } else {
                    event.subject.sendMessage("您未绑定 userId，请使用 /绑定 <userId> 指令进行绑定。")
                }
            }
            else -> {
                event.subject.sendMessage("使用方式：/兑换 <userId> <code> 或 /兑换 <code>")
            }
        }
    }

    private suspend fun sendRedeemRequest(contact: Contact, qq: Long, userId: String, code: String) {
        val history = UsedCodeData.usedCodes.getOrPut(qq) { mutableListOf() }

        if (code in history) {
            contact.sendMessage("换过了")
            return
        }

        val requestBody = mapOf(
            "appId" to ConfigData.appId,
            "userId" to userId,
            "code" to code
        )

        val jsonBody = Gson().toJson(requestBody)

        // 添加日志用于调试
        logger.info("发送兑换请求: appId=${ConfigData.appId}, userId=$userId, code=$code")

        val request = Request.Builder()
            .url(ConfigData.apiUrl)
            .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Origin", "https://redeem.bd2.pmang.cloud")
            .header("Referer", "https://redeem.bd2.pmang.cloud/")
            .header("Accept", "application/json")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logger.error("请求失败: ${e.message}", e)
                handleApiResponse(contact, 0, Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyStr = response.body?.string() ?: ""

                // 添加详细日志
                logger.info("API响应: code=${response.code}, body=$bodyStr")

                if (response.isSuccessful) {
                    try {
                        val json = JsonParser.parseString(bodyStr).asJsonObject

                        if (!json.has("error")) {
                            // 兑换成功，保存记录
                            if (!history.contains(code)) {
                                if (history.size >= 5) history.removeAt(0)
                                history.add(code)
                                UsedCodeData.save()
                                logger.info("保存兑换记录: qq=$qq, code=$code")
                            }
                        }

                        handleApiResponse(contact, response.code, Result.success(bodyStr))
                    } catch (e: Exception) {
                        logger.error("解析响应失败: ${e.message}", e)
                        handleApiResponse(contact, response.code, Result.failure(e))
                    }
                } else {
                    handleApiResponse(contact, response.code, Result.failure(Exception("HTTP失败：${response.code}")))
                }
            }
        })
    }

    private fun handleApiResponse(contact: Contact, statusCode: Int, result: Result<String>) {
        launch {
            result.onSuccess { data ->
                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    val errorMessage = when {
                        json.has("error") -> {
                            val error = json.get("error")
                            when {
                                error.isJsonObject -> error.asJsonObject.get("message")?.asString ?: ""
                                error.isJsonPrimitive -> error.asJsonPrimitive.asString
                                else -> ""
                            }
                        }
                        else -> ""
                    }

                    val feedback = when (errorMessage) {
                        "InvalidCode" -> "打错了吧"
                        "AlreadyUsed" -> "换过了吧"
                        "IncorrectUser" -> "。名字绑错了"
                        "ExpiredCode" -> "过期了吧"
                        "UnavailableCode" -> "还不让用"
                        "BadRequest" -> "请求格式错误，请检查用户名格式是否正确"
                        "" -> "兑换成功！"
                        else -> "发生未知错误：$errorMessage"
                    }
                    contact.sendMessage(feedback)
                } catch (e: Exception) {
                    logger.error("响应解析失败: ${e.message}", e)
                    contact.sendMessage("响应解析失败：${e.message}")
                }
            }.onFailure {
                logger.error("请求失败，状态码 $statusCode: ${it.message}", it)
                contact.sendMessage("请求失败，状态码 $statusCode：${it.message}")
            }
        }
    }

    private suspend fun handleQueryAllBindings(event: MessageEvent) {
        if (BindingData.userBindings.isEmpty()) {
            event.subject.sendMessage("当前没有任何绑定信息。")
        } else {
            val msg = BindingData.userBindings.entries.joinToString("\n") { (qq, userId) ->
                "$qq -> $userId"
            }
            event.subject.sendMessage("所有绑定信息如下：\n$msg")
        }
    }

    private suspend fun handleQueryBindOther(event: MessageEvent) {
        val at = event.message.firstOrNull { it is At } as? At
        if (at == null) {
            event.subject.sendMessage("请 @ 一位用户以查询绑定信息。")
            return
        }

        val targetId = at.target
        val userId = BindingData.userBindings[targetId]
        if (userId != null) {
            event.subject.sendMessage("该用户绑定的 userId 是：$userId")
        } else {
            event.subject.sendMessage("该用户未绑定 userId。")
        }
    }

    private suspend fun handleQueryRecordCommand(event: MessageEvent) {
        val at = event.message.firstOrNull { it is At } as? At
        val targetId = at?.target ?: event.sender.id
        val records = UsedCodeData.usedCodes[targetId]
        val name = if (targetId == event.sender.id) "你" else "该用户"

        if (records.isNullOrEmpty()) {
            event.subject.sendMessage("$name 没有记录。")
        } else {
            val msg = records.joinToString("\n") { "- $it" }
            event.subject.sendMessage("$name 最近的兑换记录如下：\n$msg")
        }
    }
}