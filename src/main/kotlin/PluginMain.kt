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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.Exception

// 配置和绑定数据管理
object ConfigData : AutoSavePluginData("ConfigData") {
    var apiUrl: String by value("https://loj2urwaua.execute-api.ap-northeast-1.amazonaws.com/prod/coupon")
    var appId: String by value("bd2-live")
}

object BindingData : AutoSavePluginData("BindingData") {
    val userBindings by value<MutableMap<Long, String>>() // QQ号 -> userId 映射
}

object PluginMain : JavaPlugin(
    JvmPluginDescription(
        id = "com.kingko.bd2redeem",
        name = "BD2redeem",
        version = "1.2.0"
    ) {
        author("KingKo")
        info("改进版兑换码插件")
    }
) {
    private val redeemAliases = listOf("/redeem", "/兑换")

    override fun onEnable() {
        ConfigData.reload()
        BindingData.reload()
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
            command == "/绑定信息" -> handleListBindings(event)
            command.startsWith("/查询绑定") -> handleQueryBinding(event)
            redeemAliases.any { command.startsWith(it) } -> handleRedeemCommand(event, command)
        }
    }

    private suspend fun handleBindCommand(event: MessageEvent, command: String) {
        val userId = command.substringAfter("/绑定 ").trim()
        val existingUserid = BindingData.userBindings[event.sender.id]
        if (userId.isNotEmpty()) {
            if (existingUserid != null) {
                event.subject.sendMessage("你已绑定：$existingUserid 。请先解绑")
            } else {
                BindingData.userBindings[event.sender.id] = userId
                event.subject.sendMessage("已成功绑定 userId：$userId")
            }
        } else {
            event.subject.sendMessage("绑定失败，请提供有效的 userId。")
        }
    }

    private suspend fun handleUnbindCommand(event: MessageEvent) {
        if (BindingData.userBindings.remove(event.sender.id) != null) {
            event.subject.sendMessage("已成功解除绑定。")
        } else {
            event.subject.sendMessage("您未绑定任何 userId。")
        }
    }

    private suspend fun handleListBindings(event: MessageEvent) {
        if (BindingData.userBindings.isEmpty()) {
            event.subject.sendMessage("当前没有任何绑定信息。")
        } else {
            val info = BindingData.userBindings.entries.joinToString("\n") { (qq, userId) ->
                "QQ: $qq -> userId: $userId"
            }
            event.subject.sendMessage("当前绑定信息如下：\n$info")
        }
    }

    private suspend fun handleQueryBinding(event: MessageEvent) {
        val mentions = event.message.filterIsInstance<At>()
        if (mentions.isNotEmpty()) {
            val at = mentions.first()
            val userId = BindingData.userBindings[at.target]
            if (userId != null) {
                event.subject.sendMessage("该成员绑定的 userId 是：$userId")
            } else {
                event.subject.sendMessage("该成员未绑定 userId。")
            }
        } else {
            event.subject.sendMessage("请 @ 一位成员以查询绑定信息。")
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
                sendRedeemRequest(event.subject, userId, code)
            }
            args.size == 1 -> {
                val code = args[0]
                val userId = BindingData.userBindings[event.sender.id]
                if (userId != null) {
                    sendRedeemRequest(event.subject, userId, code)
                } else {
                    event.subject.sendMessage("您未绑定 userId，请使用 /绑定 <userId> 指令进行绑定。")
                }
            }
            else -> {
                event.subject.sendMessage("使用方式：/兑换 <userId> <code> 或 /兑换 <code>")
            }
        }
    }

    private fun sendRedeemRequest(contact: Contact, userId: String, code: String) {
        val client = OkHttpClient()
        val gson = Gson()

        val jsonBody = gson.toJson(
            mapOf(
                "appId" to ConfigData.appId,
                "userId" to userId,
                "code" to code
            )
        )

        val request = Request.Builder()
            .url(ConfigData.apiUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Origin", "https://redeem.bd2.pmang.cloud")
            .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        Thread {
            try {
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    handleApiResponse(contact, response.code, Result.success(body))
                } else {
                    handleApiResponse(contact, response.code, Result.failure<String>(Exception("HTTP失败：${response.code}")))

                }
            } catch (e: Exception) {
                handleApiResponse(contact, 500, Result.failure<String>(e))
            }
        }.start()
    }

    private fun handleApiResponse(contact: Contact, statusCode: Int, result: Result<String>) {
        launch {
            result.fold(
                onSuccess = { data ->
                    try {
                        val json = JsonParser.parseString(data).asJsonObject
                        if (json.has("error")) {
                            val error = json["error"]
                            val errorMessage = when {
                                error.isJsonObject -> error.asJsonObject["message"]?.asString ?: ""
                                error.isJsonPrimitive -> error.asString
                                else -> ""
                            }
                            val feedback = when (errorMessage) {
                                "InvalidCode" -> "兑换码无效，请检查后重试。"
                                "AlreadyUsed" -> "该兑换码已被使用，请更换其他兑换码。"
                                "IncorrectUser" -> "用户ID不存在，请检查后重试。"
                                "" -> "兑换成功！"
                                else -> "发生未知错误：$errorMessage"
                            }
                            contact.sendMessage(feedback)
                        } else {
                            contact.sendMessage("兑换成功！")
                        }
                    } catch (e: Exception) {
                        contact.sendMessage("响应解析失败：${e.message}")
                    }
                },
                onFailure = {
                    contact.sendMessage("请求失败，状态码 $statusCode：${it.message}")
                }
            )
        }
    }

}
