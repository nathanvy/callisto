package ca.wonderlan.callisto.data

import java.io.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.Charset
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// Top-level so fragments can import it.
data class GroupInfo(
    val name: String,
    val high: Long?,
    val low: Long?,
    val status: String?
)

/**
 * Ultra-minimal NNTP client sufficient for v1 text-only reading/posting.
 * It supports: connect banner, GROUP, HEAD, BODY, POST, QUIT.
 *
 * NOTE: No TLS for v1. Put a TLS proxy in front if needed, or extend to use SSLSocket.
 */
class NNTPClient(host: String, port: Int, useTls: Boolean = false) : Closeable {

    private val socket: Socket
    private val reader: BufferedReader
    private val writer: BufferedWriter

    init {
        socket = if (useTls) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val s = factory.createSocket(host, port) as SSLSocket
            // (Optional) enable only modern TLS suites; defaults are usually fine
            s.startHandshake()
            s
        } else {
            Socket(host, port)
        }

        reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charset.forName("US-ASCII")))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charset.forName("US-ASCII")))

        val banner = reader.readLine() ?: throw RuntimeException("No server banner")
        if (!banner.startsWith("200") && !banner.startsWith("201")) {
            throw RuntimeException("Bad banner: $banner")
        }
    }

    /* ---------------- Group listing ---------------- */

    /** Returns all carried groups using LIST, falling back to LIST ACTIVE if needed. */
    fun listGroups(): List<GroupInfo> {
        // Try LIST first
        send("LIST")
        var first = reader.readLine() ?: throw RuntimeException("No response to LIST")
        if (first.startsWith("215")) {
            return readGroupList()
        }

        // Fallback: LIST ACTIVE
        send("LIST ACTIVE")
        first = reader.readLine() ?: throw RuntimeException("No response to LIST ACTIVE")
        if (first.startsWith("215")) {
            return readGroupList()
        }

        throw RuntimeException("Server does not support LIST / LIST ACTIVE: $first")
    }

    /** Reads lines until '.', parsing "<group> <high> <low> <status>" (tolerant of missing fields). */
    private fun readGroupList(): List<GroupInfo> {
        val out = mutableListOf<GroupInfo>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line == ".") break
            if (line.isBlank()) continue

            // Typical format: "group.name high low y|n|m|j"
            val parts = line.split(' ').filter { it.isNotBlank() }
            val name = parts.getOrNull(0) ?: continue
            val high = parts.getOrNull(1)?.toLongOrNull()
            val low  = parts.getOrNull(2)?.toLongOrNull()
            val status = parts.getOrNull(3)
            out.add(GroupInfo(name, high, low, status))
        }
        return out
    }

    /* ---------------- Group select & fetch ---------------- */

    data class GroupStat(val name: String, val count: Long, val low: Long, val high: Long)
    private var currentStat: GroupStat? = null

    fun selectGroup(name: String): GroupStat {
        send("GROUP $name")
        val line = expect(211) // "211 count low high group"
        val parts = line.split(' ').filter { it.isNotBlank() }
        val count = parts[1].toLong()
        val low   = parts[2].toLong()
        val high  = parts[3].toLong()
        val grp   = parts.getOrNull(4) ?: name
        val stat = GroupStat(grp, count, low, high)
        currentStat = stat
        return stat
    }

    fun fetchRecentArticles(maxCount: Int): List<Article> {
        val stat = currentStat ?: throw IllegalStateException("Call selectGroup first")

        // If empty or invalid range, return nothing
        if (stat.count <= 0 || stat.high <= 0 || stat.high < stat.low) {
            return emptyList()
        }

        // Clamp start to [low..high], and never below 1
        val start = maxOf(1L, maxOf(stat.low, stat.high - maxCount + 1))
        val out = mutableListOf<Article>()
        var i = start
        while (i <= stat.high) {
            val head = head(i)    // may be null if missing
            val body = body(i)    // may be null if missing
            if (head != null && body != null) {
                out.add(
                    Article(
                        number = i,
                        messageId = head["message-id"] ?: "",
                        subject  = head["subject"] ?: "",
                        from     = head["from"] ?: "",
                        date     = head["date"] ?: "",
                        body     = body.joinToString("\n")
                    )
                )
            }
            i++
        }
        return out.reversed()
    }

    /* ---------------- Article retrieval helpers ---------------- */

    private fun head(num: Long): Map<String, String>? {
        send("HEAD $num")
        val first = reader.readLine() ?: return null
        return when {
            first.startsWith("221") -> {
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == ".") break
                    if (line.isBlank()) continue
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        headers[key] = if (key in headers) headers[key] + " " + value else value
                    }
                }
                headers
            }
            // 423: No such article number in this group; 430: no such article found
            first.startsWith("423") || first.startsWith("430") -> null
            else -> throw RuntimeException("HEAD $num failed: $first")
        }
    }

    private fun body(num: Long): List<String>? {
        send("BODY $num")
        val first = reader.readLine() ?: return null
        return when {
            first.startsWith("222") -> {
                val lines = mutableListOf<String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line == ".") break
                    lines.add(if (line.startsWith("..")) line.drop(1) else line)
                }
                lines
            }
            first.startsWith("423") || first.startsWith("430") -> null
            else -> throw RuntimeException("BODY $num failed: $first")
        }
    }

    /* ---------------- Posting ---------------- */

    fun post(headers: Map<String, String>, body: List<String>): Boolean {
        send("POST")
        expect(340) // Send article to be posted
        for ((k, v) in headers) {
            writer.write("$k: $v\r\n")
        }
        writer.write("\r\n")
        for (ln in body) {
            val out = if (ln.startsWith(".")) ".$ln" else ln
            writer.write(out + "\r\n")
        }
        writer.write(".\r\n")
        writer.flush()
        val resp = reader.readLine() ?: ""
        return resp.startsWith("240")
    }

    /* ---------------- Wire helpers ---------------- */

    private fun send(cmd: String) {
        writer.write(cmd + "\r\n")
        writer.flush()
    }

    fun auth(user: String, pass: String) {
        send("AUTHINFO USER $user")
        expect(381) // more auth required
        send("AUTHINFO PASS $pass")
        expect(281) // authentication accepted
    }

    private fun expect(code: Int): String {
        val line = reader.readLine() ?: throw RuntimeException("No response for $code")
        if (!line.startsWith(code.toString())) {
            throw RuntimeException("Expected $code, got: $line")
        }
        // Keep currentStat in sync when GROUP was just called and 211 returned
        if (code == 211) {
            val parts = line.split(' ').filter { it.isNotBlank() }
            currentStat = GroupStat(
                name  = parts.getOrNull(4) ?: "",
                count = parts[1].toLong(),
                low   = parts[2].toLong(),
                high  = parts[3].toLong()
            )
        }
        return line
    }

    override fun close() {
        try { send("QUIT") } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        try { writer.close() } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }
}
