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
import io.ktor.content.default
import io.ktor.content.files
import io.ktor.content.static
import io.ktor.content.staticRootFolder
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.experimental.*
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

val FISH_EYE_URL = System.getProperty("fisheye")?: "https://tosfish.iteclientsys.local"
val FISH_EYE_URL_REST = "$FISH_EYE_URL/rest-service-fe"
val CONFLUENCE_URL = System.getProperty("confluence") ?: "https://tosconf.iteclientsys.local"
val CONFLUENCE_URL_REST = "$CONFLUENCE_URL/rest/api"
val JIRA_URL = System.getProperty("jira")?: "https://tosjira.iteclientsys.local"
val JIRA_URL_REST = "$JIRA_URL/rest/api/2"
val BITBUCKET_URL = System.getProperty("bitbucket") ?: "https://tosgit.iteclientsys.local"
val BITBUCKET_URL_REST = "$BITBUCKET_URL/rest/api/1.0"
const val DATA_FORMAT = "yyyy-MM-dd"
const val DATA_FORMAT_CONFLUENCE = "yyyy/MM/dd"
val DATA_FORMATTER = DateTimeFormatter.ofPattern(DATA_FORMAT)!!
val DATA_FORMATTER_CONFLUENCE = DateTimeFormatter.ofPattern(DATA_FORMAT_CONFLUENCE)!!

fun main(args: Array<String>) {
    val password = args[0]
    val requestAttributes = mapOf(Pair("Authorization", "Basic $password"))
    val server = embeddedServer(Netty, port = 8080) {
        install(CallLogging)
        install(ContentNegotiation) {
            gson {
                // Configure Gson here
            }
        }
        routing {
            get("/sniffer/jira/source/{source}/from/{from}/to/{to}") { request ->
                call.respond(readJiraContentModifications(call.parameters["source"],
                        long(call.parameters["from"]), long(call.parameters["to"]), requestAttributes))
            }
            get("/sniffer/confluence/source/{source}/from/{from}/to/{to}") { request ->
                    call.respond(readConfluenceContentModifications(call.parameters["source"],
                            long(call.parameters["from"]), long(call.parameters["to"]), requestAttributes))
            }
            get("/sniffer/fisheye/source/{source}/from/{from}/to/{to}") { request ->
                    call.respond(readFishCommittedLines(call.parameters["source"],
                            long(call.parameters["from"]), long(call.parameters["to"]), requestAttributes))
            }
            get("/sniffer/bitbucket/project/{project}/repository/{repository}/from/{from}/to/{to}") { request ->
                call.respond(readBitbucketCommits(call.parameters["project"], call.parameters["repository"],
                        long(call.parameters["from"]), long(call.parameters["to"]), requestAttributes))
            }
            get("/sniffer/pull-requests/project/{project}/repository/{repository}/from/{from}/to/{to}") { request ->
                call.respond(readPullRequests(call.parameters["project"], call.parameters["repository"],
                        long(call.parameters["from"]), long(call.parameters["to"]), requestAttributes))
            }
            static("sniffer") {
                staticRootFolder = File(System.getProperty("user.dir") + File.separator  + "src" + File.separator + "main")
                files("resources")
                default("resources" + File.separator +"ui.html")
            }
            get("/sniffer/confluence") {
                call.respond(readConfluenceSpaces(requestAttributes))
            }
            get("/sniffer/jira") {
                call.respond(readJiraProjects(requestAttributes))
            }
            get("/sniffer/fisheye") {
                call.respond(readFisheyeRepositories(requestAttributes))
            }
            get("/sniffer/bitbucket") {
                call.respond(readBitbucketProjects(requestAttributes))
            }
        }
    }
    server.start(wait = true)

    // there is a project define the most committees for a week
//    val start = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(4)
//    val end = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(0)
//
//    val password = args[0]
//    val requestAttributes = mapOf(Pair("Authorization", "Basic $password"))
//    runBlocking {
//        println(readBitbucketCommits("tos", "alertmanagement",
//                start, end, requestAttributes))
//    }
    //println(readFishCommittedLines("thinkpipes", start, end, requestAttributes))
}

// select revisions where date in [2008-03-08, 2008-04-08]
// https://confluence.atlassian.com/fisheye/eyeql-reference-guide-298976796.html?_ga=2.197841449.789708941.1533913490-493128539.1533552482
// https://tosfish.iteclientsys.local/rest-service-fe/tos?
// fishEye, https://docs.atlassian.com/fisheye-crucible/3.3.3/wadl/fisheye.html#d2e338
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/changesetList/tos
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/changeset/tos/a9e174976d0586555c1d10c375e48490671d025c
// https://tosfish.iteclientsys.local/rest-service-fe/revisionData-v1/revisionInfo/tos/?path=AdminGUI/src/main/java/com/devexperts/tos/ui/admin/riskmonitor/RiskPanel.java&revision=7f8cdb314790e65e82bc6ca1af846b7e259e88bf
suspend fun readFishCommittedLines(project: String?, fromTime: Long?, toTime: Long?,
                           requestAttributes: Map<String, String>): List<UserStats> {
    val from = formatTime(fromTime ?: 0)
    val to = formatTime(toTime ?: 0)
    val emailLines: MutableMap<String, Int> = mutableMapOf()
    val query = URLEncoder.encode("select revisions where date in [$from, $to) return author, linesAdded, linesRemoved", "utf-8")
    val statistics = get("$FISH_EYE_URL_REST/search-v1/queryAsRows/$project?query=$query", requestAttributes).asJsonObject
    for (row in statistics["row"] as JsonArray) {
        val item = jA(jO(row)["item"])
        val author = item[0].asString
        val email = author.substring(author.indexOf('<') + 1, author.length - 1)
        emailLines[email] = emailLines.getOrDefault(email, 0) + item[1].asInt - item[2].asInt
    }
    return resolveFisheyeUsers(emailLines, requestAttributes)
}

suspend fun readFisheyeRepositories(requestAttributes: Map<String, String>): Set<String> {
    val repositories = get("$FISH_EYE_URL_REST/repositories-v1", requestAttributes).asJsonObject["repository"].asJsonArray
    return repositories.map { repository -> repository.asJsonObject["name"].asString }.toSet()
}

suspend fun resolveFisheyeUsers(emailLines: Map<String, Int>, requestAttributes: Map<String, String>): List<UserStats> {
    val query = "$JIRA_URL_REST/user/search?maxResults=1000&username"
    val emailUser: MutableMap<String, User> = ConcurrentHashMap()
    val devexpertUsersJson = get("$query=devexperts.com", requestAttributes).asJsonArray
    val tdaUsersJson = get("$query=tdameritrade.com", requestAttributes).asJsonArray
    runBlocking {
        devexpertUsersJson.map { u -> u.asJsonObject }
                .map { user -> User(user["name"].asString, user["displayName"].asString, user["emailAddress"].asString.toLowerCase()) }
                .forEach { u -> emailUser[u.email] = u }
        tdaUsersJson.map { u -> u.asJsonObject }
                .map { user -> User(user["name"].asString, user["displayName"].asString, user["emailAddress"].asString.toLowerCase()) }
                .forEach { u -> emailUser[u.email] = u }
    }

    return emailLines.map { (e,l) ->
        UserStats(emailUser[e.toLowerCase()] ?: User(e.toLowerCase(), e.toLowerCase()), l) }.toList()
}

// confluence
//  https://docs.atlassian.com/ConfluenceServer/rest/6.10.1/#content-getContentById
suspend fun readConfluenceContentModifications(space: String?, fromTime: Long?, toTime: Long?,
                                               requestAttributes: Map<String, String>): List<UserStats> {
    val from = formatTime(fromTime ?: 0, DATA_FORMATTER_CONFLUENCE)
    val to = formatTime(toTime ?: 0, DATA_FORMATTER_CONFLUENCE)
    val query = URLEncoder.encode("space=$space and (lastmodified >= \"$from\" and lastmodified <= \"$to\" " +
            "or created >= \"$from\" and created <= \"$to\")", "utf-8")
    val statistics = get("$CONFLUENCE_URL_REST/content/search?cql=$query&expand=version", requestAttributes)
    val writtenLines: MutableMap<User, Int> = mutableMapOf()
    for (result in jA(jO(statistics)["results"])) {
        val updater = jO(jO(jO(result)["version"])["by"])
        val user = User(updater["username"].asString, updater["displayName"].asString)
        writtenLines[user] = 1 + writtenLines.getOrDefault(user, 0)
    }
    return writtenLines.map{ (k,v) -> UserStats(k, v) }.toList()
}

suspend fun readConfluenceSpaces(requestAttributes: Map<String, String>): List<String> {
    val spaces = get("$CONFLUENCE_URL_REST/space?limit=300&type=global", requestAttributes).asJsonObject
    return (spaces["results"]).asJsonArray.map { result -> (result.asJsonObject)["key"].asString }.toList().sorted()
}

// jira
// use JiraQL for all
// https://docs.atlassian.com/software/jira/docs/api/REST/7.6.1/?_ga=2.49922081.602716363.1533552482-493128539.1533552482#api/2/search-search
// https://tosjira.iteclientsys.local/rest/api/2/search
// project = alerts project = alerts and (updated >= 2018-08-01 and  updated <= 2018-08-03 or created >= 2018-08-01 and  created <= 2018-08-03)
suspend fun readJiraContentModifications(project: String?, fromTime: Long?, toTime: Long?,
                                 requestAttributes: Map<String, String>): List<UserStats> {
    val from = formatTime(fromTime ?: 0)
    val to = formatTime(toTime ?: 0)
    val query = URLEncoder.encode("project=$project and (created >= $from and created <= $to)", "utf-8")
    val statistics = get("$JIRA_URL_REST/search?jql=$query&fields=creator&maxResults=1000", requestAttributes)
    val createdTickets: MutableMap<User, Int> = mutableMapOf()
    for(issue in jA(jO(statistics)["issues"])) {
        val creator = jO(jO(jO(issue)["fields"])["creator"])
        val author = User(creator["name"].asString, creator["displayName"].asString)
        createdTickets[author] = 1 + createdTickets.getOrDefault(author, 0)
    }
    return createdTickets.map{ (k,v) -> UserStats(k, v) }.toList()
}

suspend fun readJiraProjects(requestAttributes: Map<String, String>): Set<String> {
    val projects = get("$JIRA_URL_REST/project", requestAttributes).asJsonArray
    return projects.map { p -> jO(p)["key"].asString }.toSet()
}

suspend fun readBitbucketProjects(requestAttributes: Map<String, String>): List<String> {
    val repos = blockingGet("$BITBUCKET_URL_REST/repos?limit=200", requestAttributes).asJsonObject["values"].asJsonArray
    return repos.filter { repo -> jO(jO(repo)["project"])["type"].asString != "PERSONAL" }
            .map { repo -> jO(jO(repo)["project"])["key"].asString + "/" + jO(repo)["slug"].asString }.toList().sorted()
}

suspend fun readPullRequests(project: String?, repository: String?, fromTime: Long?, toTime: Long?,
                             requestAttributes: Map<String, String>): List<PullRequest> {
    var start = 0
    val limit = 50
    val from = fromTime?:0
    val to = toTime?:0
    val pullRequests : MutableList<PullRequest> = ArrayList()
    out@ while (true) {
        val url = "$BITBUCKET_URL_REST/projects/$project/repos/$repository/pull-requests?state=ALL&limit=$limit&start=$start"
        val pullRequestsRes = blockingGet(url, requestAttributes).asJsonObject
        val pullRequestsArray = pullRequestsRes.asJsonObject["values"].asJsonArray
        if (pullRequestsArray.size() == 0) break
        for (pullRequest in pullRequestsArray) {
            val pullRequestTime = jO(pullRequest)["updatedDate"].asString.toLong()
            if (pullRequestTime > to) continue
            if (pullRequestTime < from)
                break@out
            val userJson = jO(pullRequest)["author"].asJsonObject["user"].asJsonObject
            val creator = User(userJson["name"].asString, userJson["displayName"].asString)
            pullRequests.add(PullRequest(creator, jO(pullRequest)["reviewers"].asJsonArray
                .filter { rv -> rv.asJsonObject["approved"].asBoolean }
                .map { rv -> rv.asJsonObject["user"].asJsonObject }
                .map { jrv -> User(jrv["name"].asString, jrv["displayName"].asString) }
               .toSet()))
        }
        if (pullRequestsRes["isLastPage"].asBoolean) break
        start += limit
    }
    return pullRequests
}

// https://tosgit.iteclientsys.local/rest/api/1.0/projects/tos/repos/alertmanagement/branches?limit=200
// https://tosgit.iteclientsys.local/rest/api/1.0/projects/tos/repos/alertmanagement/commits?until=release/1934_cucumber&merges=exclude
suspend fun readBitbucketCommits(project: String?, repository: String?, fromTime: Long?, toTime: Long?,
                          requestAttributes: Map<String, String>): List<UserStats> {

    val branches = blockingGet("$BITBUCKET_URL_REST/projects/$project/repos/$repository/branches?limit=200",
            requestAttributes).asJsonObject["values"].asJsonArray
    val commitUserList : MutableList<Deferred<Map<String, User>>> = ArrayList()
    val userCommits : MutableMap<User, Int> = HashMap()

    val IO = newFixedThreadPoolContext(Math.max(branches.size()/2, 5), "IO")
    for(branch in branches) {
        val lastCommitSHA = branch.asJsonObject["latestCommit"].asString
        commitUserList.add(async {
            withContext(IO) {
                readBranchCommits(project, repository, lastCommitSHA, fromTime, toTime, requestAttributes)
            }
        })
    }
    for(commitUser in commitUserList) {
        for((k, u) in commitUser.await()) {
            userCommits[u] = userCommits.getOrDefault(u, 0) + 1
        }
    }
    IO.close()
    return userCommits.map{ (k,v) -> UserStats(k, v) }.toList()
}

suspend fun readBranchCommits(project: String?, repository: String?, lastCommit: String, fromTime: Long?, toTime: Long?,
                              requestAttributes: Map<String, String>): Map<String, User> {
    var start = 0
    val limit = 10
    val from = fromTime?:0
    val to = toTime?:0
    val commitUser : MutableMap<String, User> = HashMap()
    out@ while (true) {
        val url = "$BITBUCKET_URL_REST/projects/$project/repos/$repository/commits?merges=exclude&limit=$limit&start=$start&until=$lastCommit"
        val values = blockingGet(url, requestAttributes)
        if (values.asJsonObject["values"] == null) continue
        val commits = values.asJsonObject["values"].asJsonArray
        if (commits.size() == 0 || values.asJsonObject["isLastPage"].asBoolean) break
        for (commit in commits) {
            val commitTime = jO(commit)["authorTimestamp"].asString.toLong()
            if (commitTime > to) continue
            if (commitTime < from) break@out
            val commitUserSHA = jO(commit)["id"].asString
            if (commitUser[commitUserSHA] == null) {
                val author = jO(commit)["author"].asJsonObject
                val user = User(author["name"].asString, author["displayName"].asString)
                commitUser[commitUserSHA] = user
            }
        }
        start += limit
    }
    return commitUser
}


fun blockingGet(url: String, requestAttributes: Map<String, String>): JsonElement {
    return runBlocking {
        get(url, requestAttributes)
    }
}

suspend fun get(url: String, requestAttributes: Map<String, String>): JsonElement {
    val client = HttpClient(Apache) {
        install(JsonFeature) {
            serializer = GsonSerializer {
                serializeNulls()
                disableHtmlEscaping()
            }
        }
    }
    return client.get(url) {
        accept(ContentType.Application.Json)
        headers {
            requestAttributes.forEach { k, v -> header(k, v) }
        }
    }
}

data class User(val id: String, val name: String, val email: String = "")
data class UserStats(val user: User, val stats: Int)
data class PullRequest(val creator: User, val reviewers: Set<User>)

fun jO(any: Any): JsonObject = any as JsonObject
fun jA(any: Any): JsonArray = any as JsonArray
fun long(value: String?) = value?.toLong() ?: 0
fun formatTime(time: Long) = formatTime(time, DATA_FORMATTER)
fun formatTime(time: Long, dateTimeFormatter: DateTimeFormatter) =
        dateTimeFormatter.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(time),
        ZoneOffset.ofHours(3)))