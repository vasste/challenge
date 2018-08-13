import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.experimental.runBlocking
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

fun main(args: Array<String>) {
    val password = args[0]
    val requestAttributes = mapOf(Pair("Authorization", "Basic $password"))
    val server = embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            gson {
                // Configure Gson here
            }
        }
        routing {
            get("/spaces") {
                call.respond(readConfluenceSpaces(requestAttributes))
            }
            get("/projects") {
                call.respond(readJiraProjects(requestAttributes))
            }
        }
    }
    server.start(wait = true)

    // there is a project define the most committees for a week
//    val start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
//    val end = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(0)
//
//    val password = args[0]
//    val requestAttributes = mapOf(Pair("Authorization", "Basic $password"))
//    println(readConfluenceSpaces(requestAttributes))
    //println(readGitCommittedLines("DXLAB", start, end, requestAttributes))
}
val FISH_EYE_URL = System.getProperty("fisheye")!!
val FISH_EYE_URL_REST = "$FISH_EYE_URL/rest-service-fe"
val CONFLUENCE_URL = System.getProperty("confluence")!!
val CONFLUENCE_URL_REST = "$CONFLUENCE_URL/rest/api"
val JIRA_URL = System.getProperty("jira")!!
val JIRA_URL_REST = "$JIRA_URL/rest/api/2"
const val DATA_FORMAT = "yyyy-MM-dd"
val DATA_FORMATTER = DateTimeFormatter.ofPattern(DATA_FORMAT)!!

// select revisions where date in [2008-03-08, 2008-04-08]
// https://confluence.atlassian.com/fisheye/eyeql-reference-guide-298976796.html?_ga=2.197841449.789708941.1533913490-493128539.1533552482
// https://tosfish.iteclientsys.local/rest-service-fe/tos?
// fishEye, https://docs.atlassian.com/fisheye-crucible/3.3.3/wadl/fisheye.html#d2e338
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/changesetList/tos
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/changeset/tos/a9e174976d0586555c1d10c375e48490671d025c
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/revisionInfo/tos/?path=AdminGUI/src/main/java/com/devexperts/tos/ui/admin/riskmonitor/RiskPanel.java&revision=7f8cdb314790e65e82bc6ca1af846b7e259e88bf
fun readGitCommittedLines(project: String, fromTime: Long, toTime: Long, requestAttributes: Map<String, String>): Map<String, Int> {
    val from = formatTime(fromTime)
    val to = formatTime(toTime)
    var userLines: Map<String, Int> = mutableMapOf()
    val query = URLEncoder.encode("select revisions where date in [$from, $to) return author, linesAdded, linesRemoved", "utf-8")
    val statistics = jO(get("$FISH_EYE_URL_REST/search-v1/queryAsRows/$project?query=$query", requestAttributes))
    for (row in statistics["row"] as JsonArray) {
        val item = jA(jO(row)["item"])
        val lines = userLines.getOrDefault(item[0].asString, 0)
        userLines = userLines.plus(Pair(item[0].asString, lines + item[1] as Int + item[2] as Int))
    }
    return userLines
}

// confluence
//  https://docs.atlassian.com/ConfluenceServer/rest/6.10.1/#content-getContentById
fun readConfluenceContentModifications(space: String, fromTime: Long, toTime: Long,
                                       requestAttributes: Map<String, String>): Map<User, Int> {
    val from = formatTime(fromTime)
    val to = formatTime(toTime)
    val query = URLEncoder.encode("space=$space and (lastmodified >= $from and lastmodified <= $to " +
            "or created >= $from and created <= $to)", "utf-8")
    val statistics = get("$CONFLUENCE_URL_REST/content/search?cql=$query&expand=version", requestAttributes)
    var writtenLines: Map<User, Int> = mutableMapOf()
    for (result in jA(jO(statistics)["results"])) {
        val updater = jO(jO(jO(result)["version"])["by"])
        val user = User(updater["username"].asString, updater["displayName"].asString)
        writtenLines = writtenLines.plus(Pair(user, 1 + writtenLines.getOrDefault(user, 0)))
    }
    return writtenLines
}

fun readConfluenceSpaces(requestAttributes: Map<String, String>): Set<String> {
    val spaces = get("$CONFLUENCE_URL_REST/space?limit=300&type=global", requestAttributes).asJsonObject
    return (spaces["results"]).asJsonArray.map { result -> (result.asJsonObject)["key"].asString }.toSet()
}

// jira
// use JiraQL for all
// https://docs.atlassian.com/software/jira/docs/api/REST/7.6.1/?_ga=2.49922081.602716363.1533552482-493128539.1533552482#api/2/search-search
// https://tosjira.iteclientsys.local/rest/api/2/search
// project = alerts project = alerts and (updated >= 2018-08-01 and  updated <= 2018-08-03 or created >= 2018-08-01 and  created <= 2018-08-03)
fun readJiraContentModifications(project: String, fromTime: Long, toTime: Long,
                                 requestAttributes: Map<String, String>): Map<User, Int> {
    val from = formatTime(fromTime)
    val to = formatTime(toTime)
    val query = URLEncoder.encode("project=$project and (updated >= $from and updated <= $to or " +
            "created >= $from and created <= $to)", "utf-8")
    val statistics = get("$JIRA_URL_REST/search?jql=$query&fields=changelog&expand=changelog&maxResults=500", requestAttributes)
    var modifiedTimes: Map<User, Int> = mutableMapOf()
    for(issue in jA(jO(statistics)["issues"])) {
        for(change in jA(jO(jO(issue)["changelog"])["histories"])) {
            val author = User(jO(jO(change)["author"])["name"].asString, jO(jO(change)["author"])["displayName"].asString)
            modifiedTimes = modifiedTimes.plus(Pair(author, 1 + modifiedTimes.getOrDefault(author, 0)))
        }
    }
    return modifiedTimes
}

fun readJiraProjects(requestAttributes: Map<String, String>): Set<String> {
    val projects = get("$JIRA_URL_REST/project", requestAttributes).asJsonArray
    return projects.map { p -> jO(p)["key"].asString }.toSet()
}

fun get(url: String, requestAttributes: Map<String, String>): JsonElement {
    val client = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
    }
    return runBlocking {
        client.get<JsonElement>(url) {
            accept(ContentType.Application.Json)
            headers {
                requestAttributes.forEach { k, v -> header(k, v) }
            }
        }
    }
}

data class User(val id: String, val name: String)

fun jO(any: Any): JsonObject = any as JsonObject
fun jA(any: Any): JsonArray = any as JsonArray
fun formatTime(time: Long) = DATA_FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneOffset.UTC))