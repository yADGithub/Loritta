package com.mrpowergamerbr.loritta.frontend.views.subviews.api.serverlist

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.*
import com.google.gson.JsonObject
import com.mongodb.client.model.Filters
import com.mrpowergamerbr.loritta.Loritta
import com.mrpowergamerbr.loritta.frontend.views.LoriWebCodes
import com.mrpowergamerbr.loritta.frontend.views.subviews.api.NoVarsView
import com.mrpowergamerbr.loritta.utils.loritta
import com.mrpowergamerbr.loritta.utils.lorittaShards
import com.mrpowergamerbr.loritta.utils.oauth2.TemmieDiscordAuth
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.requests.Route
import org.jooby.MediaType
import org.jooby.Request
import org.jooby.Response

class APIJoinServerView : NoVarsView() {
	override fun handleRender(req: Request, res: Response): Boolean {
		return req.path().matches(Regex("^/api/v1/server-list/join"))
	}

	override fun render(req: Request, res: Response): String {
		res.type(MediaType.json)
		val guildId = req.param("guildId").value()

		var discordAuth: TemmieDiscordAuth? = null
		var userIdentification: TemmieDiscordAuth.UserIdentification? = null
		if (req.session().isSet("discordAuth")) {
			discordAuth = Loritta.GSON.fromJson<TemmieDiscordAuth>(req.session()["discordAuth"].value())
			try {
				discordAuth.isReady(true)
				userIdentification = discordAuth.getUserIdentification() // Vamos pegar qualquer coisa para ver se não irá dar erro
			} catch (e: Exception) {
				req.session().unset("discordAuth")
			}
		}

		if (discordAuth == null || userIdentification == null) {
			val payload = JsonObject()
			payload["api:code"] = LoriWebCodes.UNAUTHORIZED
			return payload.toString()
		}

		val guild = lorittaShards.getGuildById(guildId)

		if (guild == null) {
			val payload = JsonObject()
			payload["api:code"] = LoriWebCodes.UNKNOWN_GUILD
			return payload.toString()
		}

		val serverConfig = loritta.serversColl.find(
				Filters.eq("_id", guildId)
		).firstOrNull()

		if (serverConfig == null) {
			val payload = JsonObject()
			payload["api:code"] = LoriWebCodes.NOT_IN_GUILD
			return payload.toString()
		}

		if (!guild.selfMember.hasPermission(Permission.CREATE_INSTANT_INVITE)) {
			val payload = JsonObject()
			payload["api:code"] = LoriWebCodes.MISSING_PERMISSION
			return payload.toString()
		}

		addMemberToServer(guild, userIdentification.id, discordAuth.accessToken!!)

		val payload = JsonObject()
		payload["api:code"] = LoriWebCodes.SUCCESS
		return payload.toString()
	}

	/**
	 * Adiciona um membro em uma guild do Discord
	 */
	fun addMemberToServer(guild: Guild, userId: String, accessToken: String, nickname: String? = null) {
		val payload = JsonObject()
		payload["access_token"] = accessToken

		if (nickname != null)
			payload["nick"] = nickname

		println(payload.toString())

		println(Route.Guilds.MODIFY_MEMBER.compile("a", "b").baseRoute)
		val body = HttpRequest.put("https://discordapp.com/api/v6/guilds/${guild.id}/members/$userId")
				.header("User-Agent", "DiscordBot (https://github.com/LorittaBot/Loritta, 0)")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bot ${Loritta.config.clientToken}")
				.send(payload.toString())
				.body()

		println(body)
	}
}