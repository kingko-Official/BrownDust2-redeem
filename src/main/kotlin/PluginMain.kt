package org.example.mirai.plugin

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.github.kittinunf.fuel.httpPost
import com.github.kittinunf.result.Result
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.plugin.jvm.JavaPlugin
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.globalEventChannel

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
    private val redeemAliases = listOf("/redeem", "/兑换") // 为兑换指令增加别名

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
            redeemAliases.any { command.startsWith(it) } -> handleRedeemCommand(event, command)
        }
    }

    private suspend fun handleBindCommand(event: MessageEvent, command: String) {
        val userId = command.substringAfter("/绑定 ").trim()
        if (userId.isNotEmpty()) {
            BindingData.userBindings[event.sender.id] = userId
            event.subject.sendMessage("已成功绑定 userId：$userId")
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
        val requestBody = mapOf(
            "appId" to ConfigData.appId,
            "userId" to userId,
            "code" to code
        )

        ConfigData.apiUrl.httpPost()
            .header(mapOf(
                "Content-Type" to "application/json",
                "Origin" to "https://redeem.bd2.pmang.cloud"
            ))
            .body(Gson().toJson(requestBody))
            .responseString { _, response, result ->
                handleApiResponse(contact, response.statusCode, result)
            }
    }

    private fun handleApiResponse(contact: Contact, statusCode: Int, result: Result<String, Exception>) {
        launch {
            when (result) {
                is Result.Success -> {
                    val data = result.get()
                    try {
                        val json = JsonParser.parseString(data).asJsonObject
                        if (json.has("error")) {
                            val errorElement = json.get("error")
                            val errorMessage = when {
                                errorElement.isJsonObject -> {
                                    errorElement.asJsonObject.get("message")?.asString ?: ""
                                }
                                errorElement.isJsonPrimitive -> {
                                    errorElement.asJsonPrimitive.asString
                                }
                                else -> ""
                            }

                            // 根据 errormessage 的内容反馈对应信息
                            val feedback = when (errorMessage) {
                                "InvalidCode" -> "兑换码无效，请检查后重试。"
                                "AlreadyUsed" -> "该兑换码已被使用，请更换其他兑换码。"
                                "IncorrectUser" -> "用户ID不存在，请检查后重试"
                                "" -> "兑换成功！" // errormessage 为空，表示成功
                                else -> "发生未知错误：$errorMessage"
                            }
                            contact.sendMessage(feedback)
                        } else {
                            contact.sendMessage("兑换成功！") // 没有 "error" 字段，默认成功
                        }
                    } catch (e: Exception) {
                        contact.sendMessage("响应解析失败：${e.message}")
                    }
                }
                is Result.Failure -> {
                    contact.sendMessage("请求失败，状态码 $statusCode：${result.getException().message}")
                }
            }
        }
    }
}
