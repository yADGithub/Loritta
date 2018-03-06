package com.mrpowergamerbr.loritta.frontend.views.subviews.api.serverlist

import com.github.salomonbrys.kotson.*
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mongodb.client.model.*
import com.mongodb.client.model.Aggregates.addFields
import com.mrpowergamerbr.loritta.Loritta
import com.mrpowergamerbr.loritta.Loritta.Companion.GSON
import com.mrpowergamerbr.loritta.frontend.views.subviews.api.NoVarsRequireAuthView
import com.mrpowergamerbr.loritta.frontend.views.subviews.api.NoVarsView
import com.mrpowergamerbr.loritta.userdata.ServerConfig
import com.mrpowergamerbr.loritta.utils.JSON_PARSER
import com.mrpowergamerbr.loritta.utils.loritta
import com.mrpowergamerbr.loritta.utils.lorittaShards
import com.mrpowergamerbr.loritta.utils.oauth2.TemmieDiscordAuth
import net.dv8tion.jda.core.OnlineStatus
import org.jooby.MediaType
import org.jooby.Request
import org.jooby.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Whitelist
import java.util.*

class APIGetServersView : NoVarsView() {
	override fun handleRender(req: Request, res: Response): Boolean {
		return req.path().matches(Regex("^/api/v1/server-list/get-servers"))
	}

	override fun render(req: Request, res: Response): String {
		res.type(MediaType.json)
		var userIdentification: TemmieDiscordAuth.UserIdentification? = null
		if (req.session().isSet("discordAuth")) {
			val discordAuth = Loritta.GSON.fromJson<TemmieDiscordAuth>(req.session()["discordAuth"].value())
			try {
				discordAuth.isReady(true)
				userIdentification = discordAuth.getUserIdentification() // Vamos pegar qualquer coisa para ver se não irá dar erro
			} catch (e: Exception) {
				req.session().unset("discordAuth")
			}
		}

		val skip = req.param("skip").intValue(0)
		val size = Math.min(req.param("size").intValue(49), 99)
		val serverType = req.param("serverType").value()

		val query = org.bson.Document.parse("{ \$addFields: { \"serverListConfig.validVotes\": { \$filter: { input: \"\$serverListConfig.votes\", as: \"item\", cond: {\$gt: [\"\$\$item.votedAt\", ${System.currentTimeMillis() - 2592000000}]}}}}}")
		if (serverType == "top") {
			val topConfigs = loritta.serversColl
					.aggregate(
							listOf(
									Aggregates.match(Filters.eq("serverListConfig.enabled", true)),
									query,
									org.bson.Document("\$addFields", org.bson.Document("length", org.bson.Document("\$size", org.bson.Document("\$ifNull", listOf("\$serverListConfig.validVotes", emptyList<Any>()))))),
									Aggregates.sort(Sorts.descending("length")),
									Aggregates.skip(skip),
									Aggregates.limit(size)
							)
					)


			val topArray = transformToJsonArray(topConfigs.toMutableList(), userIdentification)

			val samples = JsonObject()

			samples["result"] = topArray
			samples["totalCount"] = loritta.serversColl.count(
					Filters.eq("serverListConfig.enabled", true)
			)

			return samples.toString()
		} else if (serverType == "recentlyBumped") {
			val topConfigs = loritta.serversColl
					.aggregate(
							listOf(
									Aggregates.match(Filters.eq("serverListConfig.enabled", true)),
									query,
									org.bson.Document("\$addFields", org.bson.Document("length", org.bson.Document("\$size", org.bson.Document("\$ifNull", listOf("\$serverListConfig.validVotes", emptyList<Any>()))))),
									Aggregates.sort(Sorts.descending("serverListConfig.lastBump")),
									Aggregates.skip(skip),
									Aggregates.limit(size)
							)
					)


			val topArray = transformToJsonArray(topConfigs.toMutableList(), userIdentification)

			val samples = JsonObject()

			samples["result"] = topArray
			samples["totalCount"] = loritta.serversColl.count(
					Filters.eq("serverListConfig.enabled", true)
			)

			return samples.toString()
		}
		return JsonObject().toString()
	}

	fun transformToJsonArray(serverConfigs: List<ServerConfig>, userIdentification: TemmieDiscordAuth.UserIdentification?): JsonArray {
		val allServersSample = JsonArray()
		for (server in serverConfigs.toList()) {
			val guild = lorittaShards.getGuildById(server.guildId) ?: continue

			val serverSample = JsonObject()
			serverSample["id"] = server.guildId
			server.serverListConfig.description = Jsoup.clean(server.serverListConfig.description, "", Whitelist.none(), Document.OutputSettings().prettyPrint(false))
			server.serverListConfig.tagline = Jsoup.clean(server.serverListConfig.tagline, "", Whitelist.none(), Document.OutputSettings().prettyPrint(false))
			serverSample["voteCount"] = server.serverListConfig.votes.size
			serverSample["validVoteCount"] = server.serverListConfig.votes.count { it.votedAt > System.currentTimeMillis() - 2592000000}
			serverSample["canVote"] = true
			// 1 = not logged in
			// 2 = not member
			// 3 = needs to wait more than 1 hour before voting
			// 4 = needs to wait until next day
			if (userIdentification != null) {
				val isMember = guild.getMemberById(userIdentification.id) != null

				if (!isMember) {
					serverSample["canVote"] = false
					serverSample["cantVoteReason"] = 2
				} else {
					val vote = server.serverListConfig.votes
							.lastOrNull { it.id == userIdentification.id }

					if (vote == null) {
						serverSample["canVote"] = true
					} else {
						val votedAt = vote.votedAt

						val calendar = Calendar.getInstance()
						calendar.timeInMillis = votedAt
						calendar.set(Calendar.HOUR_OF_DAY, 0)
						calendar.set(Calendar.MINUTE, 0)
						calendar.add(Calendar.DAY_OF_MONTH, 1)
						val tomorrow = calendar.timeInMillis

						val canVote = System.currentTimeMillis() > tomorrow

						if (canVote) {
							serverSample["canVote"] = true
						} else {
							serverSample["canVote"] = false
							serverSample["cantVoteReason"] = 4
							serverSample["canVoteNext"] = tomorrow
						}
					}
				}
			} else {
				serverSample["canVote"] = false
				serverSample["cantVoteReason"] = 1
			}

			val serverListConfig = Gson().toJsonTree(server.serverListConfig).obj
			serverListConfig.remove("votes")
			serverSample["serverListConfig"] = serverListConfig

			serverSample["iconUrl"] = guild.iconUrl
			serverSample["invite"] = "https://google.com/"
			serverSample["name"] = guild.name
			serverSample["ownerId"] = guild.owner.user.id
			serverSample["ownerName"] = guild.owner.user.name
			serverSample["ownerDiscriminator"] = guild.owner.user.discriminator
			serverSample["memberCount"] = guild.members.size
			serverSample["onlineCount"] = guild.members.count { it.onlineStatus != OnlineStatus.OFFLINE }

			val serverEmotes = JsonArray()

			guild.emotes.forEach {
				val emote = JsonObject()
				emote["name"] = it.name
				emote["imageUrl"] = it.imageUrl
				serverEmotes.add(emote)
			}

			serverSample["serverEmotes"] = serverEmotes

			allServersSample.add(serverSample)
		}
		return allServersSample
	}
}