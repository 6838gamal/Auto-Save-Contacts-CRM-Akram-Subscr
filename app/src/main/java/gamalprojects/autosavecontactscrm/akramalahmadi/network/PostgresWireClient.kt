package gamalprojects.autosavecontactscrm.akramalahmadi.network

import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class PostgresSslMode {
    DISABLE,
    ALLOW,
    PREFER,
    REQUIRE,
    VERIFY_CA,
    VERIFY_FULL
}

data class PostgresConnectionConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val sslMode: PostgresSslMode
) {
    companion object {
        fun isPostgresUri(rawUrl: String): Boolean {
            val trimmed = rawUrl.trim().lowercase()
            return trimmed.startsWith("postgres://") ||
                trimmed.startsWith("postgresql://") ||
                trimmed.startsWith("jdbc:postgresql://")
        }

        fun parse(rawUrl: String): PostgresConnectionConfig? {
            val trimmed = rawUrl.trim()
            if (trimmed.isBlank() ||
                trimmed == "MY_POSTGRES_DATABASE_URL" ||
                !isPostgresUri(trimmed)
            ) {
                return null
            }
            val normalized = if (trimmed.startsWith("jdbc:", ignoreCase = true)) {
                trimmed.substring(5)
            } else {
                trimmed
            }

            return try {
                val uri = URI(normalized)
                val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
                val port = if (uri.port in 1..65535) uri.port else 5432
                val rawPath = uri.rawPath?.trimStart('/').orEmpty()
                val database = decodePart(rawPath).ifBlank { "postgres" }

                val rawUserInfo = uri.rawUserInfo.orEmpty()
                val colonIdx = rawUserInfo.indexOf(':')
                val username: String
                val password: String
                if (colonIdx >= 0) {
                    username = decodePart(rawUserInfo.substring(0, colonIdx)).ifBlank { "postgres" }
                    password = decodePart(rawUserInfo.substring(colonIdx + 1))
                } else {
                    username = decodePart(rawUserInfo).ifBlank { "postgres" }
                    password = ""
                }

                val queryParams = parseQueryParams(uri.rawQuery)
                val sslMode = when (queryParams["sslmode"]?.lowercase()) {
                    "disable" -> PostgresSslMode.DISABLE
                    "allow" -> PostgresSslMode.ALLOW
                    "prefer" -> PostgresSslMode.PREFER
                    "require" -> PostgresSslMode.REQUIRE
                    "verify-ca" -> PostgresSslMode.VERIFY_CA
                    "verify-full" -> PostgresSslMode.VERIFY_FULL
                    else -> if (queryParams["ssl"]?.equals("true", ignoreCase = true) == true) {
                        PostgresSslMode.REQUIRE
                    } else {
                        PostgresSslMode.PREFER
                    }
                }

                PostgresConnectionConfig(
                    host = host,
                    port = port,
                    database = database,
                    username = username,
                    password = password,
                    sslMode = sslMode
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun decodePart(raw: String): String {
            if (raw.isEmpty()) return ""
            // Preserve '+' characters inside passwords/usernames
            val safe = raw.replace("+", "%2B")
            return URLDecoder.decode(safe, "UTF-8")
        }

        private fun parseQueryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            val map = mutableMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                    val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    map[k] = v
                }
            }
            return map
        }
    }
}

data class PostgresQueryResult(
    val columns: List<String>,
    val rows: List<Map<String, String?>>
)

class PostgresException(message: String, val sqlState: String? = null) : IOException(message)

/**
 * Lightweight, Android-safe PostgreSQL Wire Protocol v3.0 client.
 * Supports TCP + TLS (SSLRequest), Cleartext, MD5, SCRAM-SHA-256 authentication,
 * simple DDL execution, and parameterized Extended Queries (Parse/Bind/Execute/Sync).
 */
class PostgresWireClient(
    private val config: PostgresConnectionConfig,
    private val timeoutMs: Int = 5_000
) : AutoCloseable {

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream

    fun connect() {
        val rawSocket = Socket()
        rawSocket.soTimeout = timeoutMs
        rawSocket.connect(InetSocketAddress(config.host, config.port), timeoutMs)

        var activeSocket: Socket = rawSocket
        if (config.sslMode != PostgresSslMode.DISABLE) {
            val rawOut = DataOutputStream(BufferedOutputStream(rawSocket.getOutputStream()))
            val rawIn = DataInputStream(BufferedInputStream(rawSocket.getInputStream()))
            // SSLRequest: length 8, code 80877103
            rawOut.writeInt(8)
            rawOut.writeInt(80877103)
            rawOut.flush()

            val sslReply = rawIn.readByte().toInt().toChar()
            if (sslReply == 'S') {
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(
                    rawSocket,
                    config.host,
                    config.port,
                    true
                ) as SSLSocket
                sslSocket.soTimeout = timeoutMs
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isIpAddress(config.host)) {
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(config.host))
                    sslSocket.sslParameters = params
                }
                sslSocket.startHandshake()
                activeSocket = sslSocket
            } else if (config.sslMode == PostgresSslMode.REQUIRE ||
                config.sslMode == PostgresSslMode.VERIFY_CA ||
                config.sslMode == PostgresSslMode.VERIFY_FULL
            ) {
                rawSocket.close()
                throw PostgresException("خادم PostgreSQL لا يدعم اتصال SSL المطلوب.")
            }
        }

        socket = activeSocket
        input = DataInputStream(BufferedInputStream(activeSocket.getInputStream()))
        output = DataOutputStream(BufferedOutputStream(activeSocket.getOutputStream()))

        sendStartupMessage()
        authenticateAndWaitReady()
    }

    fun executeSimple(sql: String): PostgresQueryResult {
        ensureConnected()
        val sqlBytes = sql.toByteArray(StandardCharsets.UTF_8)
        output.writeByte('Q'.code)
        output.writeInt(4 + sqlBytes.size + 1)
        output.write(sqlBytes)
        output.writeByte(0)
        output.flush()
        return readUntilReadyForQuery()
    }

    fun query(sql: String, params: List<String?> = emptyList()): PostgresQueryResult {
        ensureConnected()
        val sqlBytes = sql.toByteArray(StandardCharsets.UTF_8)

        // 1. Parse ('P')
        val parseBuf = ByteArrayOutputStream()
        DataOutputStream(parseBuf).use { dos ->
            dos.writeByte(0) // unnamed statement
            dos.write(sqlBytes)
            dos.writeByte(0)
            dos.writeShort(0) // let PostgreSQL infer param types
        }
        sendTypedMessage('P', parseBuf.toByteArray())

        // 2. Bind ('B')
        val bindBuf = ByteArrayOutputStream()
        DataOutputStream(bindBuf).use { dos ->
            dos.writeByte(0) // unnamed portal
            dos.writeByte(0) // unnamed statement
            dos.writeShort(0) // all text param formats
            dos.writeShort(params.size)
            for (p in params) {
                if (p == null) {
                    dos.writeInt(-1)
                } else {
                    val bytes = p.toByteArray(StandardCharsets.UTF_8)
                    dos.writeInt(bytes.size)
                    dos.write(bytes)
                }
            }
            dos.writeShort(0) // all text result formats
        }
        sendTypedMessage('B', bindBuf.toByteArray())

        // 3. Describe portal ('D')
        sendTypedMessage('D', byteArrayOf('P'.code.toByte(), 0))

        // 4. Execute ('E')
        sendTypedMessage('E', byteArrayOf(0, 0, 0, 0, 0))

        // 5. Sync ('S')
        sendTypedMessage('S', ByteArray(0))
        output.flush()

        return readUntilReadyForQuery()
    }

    override fun close() {
        try {
            if (this::output.isInitialized) {
                output.writeByte('X'.code)
                output.writeInt(4)
                output.flush()
            }
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun ensureConnected() {
        if (socket == null || socket?.isClosed == true) {
            throw PostgresException("الاتصال بقاعدة البيانات مغلق.")
        }
    }

    private fun sendStartupMessage() {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { dos ->
            dos.writeInt(196608) // Protocol 3.0
            writeCString(dos, "user")
            writeCString(dos, config.username)
            writeCString(dos, "database")
            writeCString(dos, config.database)
            writeCString(dos, "client_encoding")
            writeCString(dos, "UTF8")
            dos.writeByte(0)
        }
        val payload = body.toByteArray()
        output.writeInt(4 + payload.size)
        output.write(payload)
        output.flush()
    }

    private fun authenticateAndWaitReady() {
        var scramState: ScramClientState? = null
        var pendingError: PostgresException? = null

        while (true) {
            val (type, payload) = readBackendMessage()
            when (type) {
                'R' -> {
                    val dis = DataInputStream(payload.inputStream())
                    val authType = dis.readInt()
                    when (authType) {
                        0 -> { /* AuthenticationOk */ }
                        3 -> {
                            sendPasswordMessage(config.password.toByteArray(StandardCharsets.UTF_8), nullTerminated = true)
                        }
                        5 -> {
                            val salt = ByteArray(4)
                            dis.readFully(salt)
                            val md5Hash = computePostgresMd5(config.username, config.password, salt)
                            sendPasswordMessage(md5Hash.toByteArray(StandardCharsets.UTF_8), nullTerminated = true)
                        }
                        10 -> {
                            val mechanisms = readCStringList(payload, 4)
                            if (mechanisms.none { it == "SCRAM-SHA-256" }) {
                                throw PostgresException("آلية المصادقة المطلوبة غير مدعومة: $mechanisms")
                            }
                            scramState = ScramClientState(config.password)
                            val clientFirst = scramState.createClientFirstMessage()
                            val msg = ByteArrayOutputStream()
                            DataOutputStream(msg).use { dos ->
                                writeCString(dos, "SCRAM-SHA-256")
                                val bytes = clientFirst.toByteArray(StandardCharsets.UTF_8)
                                dos.writeInt(bytes.size)
                                dos.write(bytes)
                            }
                            sendTypedMessage('p', msg.toByteArray())
                            output.flush()
                        }
                        11 -> {
                            val serverFirst = String(payload, 4, payload.size - 4, StandardCharsets.UTF_8)
                            val state = scramState ?: throw PostgresException("حالة SCRAM غير صالحة.")
                            val clientFinal = state.processServerFirstMessage(serverFirst)
                            sendTypedMessage('p', clientFinal.toByteArray(StandardCharsets.UTF_8))
                            output.flush()
                        }
                        12 -> {
                            // AuthenticationSASLFinal
                        }
                        else -> throw PostgresException("نوع مصادقة PostgreSQL غير مدعوم: $authType")
                    }
                }
                'S', 'K', 'N' -> { /* ParameterStatus / BackendKeyData / NoticeResponse */ }
                'E' -> {
                    pendingError = parseErrorResponse(payload)
                }
                'Z' -> {
                    if (pendingError != null) throw pendingError
                    return
                }
                else -> {
                    if (pendingError != null) throw pendingError
                }
            }
            if (pendingError != null && type == 'E') {
                // In startup errors Postgres closes socket after ErrorResponse without ReadyForQuery
                throw pendingError
            }
        }
    }

    private fun readUntilReadyForQuery(): PostgresQueryResult {
        var columns: List<String> = emptyList()
        val rows = mutableListOf<Map<String, String?>>()
        var pendingError: PostgresException? = null

        while (true) {
            val (type, payload) = readBackendMessage()
            when (type) {
                'T' -> { // RowDescription
                    val dis = DataInputStream(payload.inputStream())
                    val fieldCount = dis.readUnsignedShort()
                    val names = ArrayList<String>(fieldCount)
                    for (i in 0 until fieldCount) {
                        names.add(readCString(dis))
                        dis.readInt() // tableOid
                        dis.readShort() // columnAttrNum
                        dis.readInt() // typeOid
                        dis.readShort() // typeSize
                        dis.readInt() // typeModifier
                        dis.readShort() // formatCode
                    }
                    columns = names
                }
                'D' -> { // DataRow
                    val dis = DataInputStream(payload.inputStream())
                    val colCount = dis.readUnsignedShort()
                    val row = LinkedHashMap<String, String?>(colCount)
                    for (i in 0 until colCount) {
                        val len = dis.readInt()
                        val colName = columns.getOrNull(i) ?: "col_$i"
                        if (len == -1) {
                            row[colName] = null
                        } else {
                            val bytes = ByteArray(len)
                            dis.readFully(bytes)
                            row[colName] = String(bytes, StandardCharsets.UTF_8)
                        }
                    }
                    rows.add(row)
                }
                '1', '2', '3', 'C', 'I', 'n', 'N', 'S', 't' -> {
                    // ParseComplete, BindComplete, CloseComplete, CommandComplete, EmptyQueryResponse, NoData, Notice, ParameterStatus
                }
                'E' -> {
                    pendingError = parseErrorResponse(payload)
                }
                'Z' -> {
                    if (pendingError != null) throw pendingError
                    return PostgresQueryResult(columns, rows)
                }
            }
        }
    }

    private fun sendPasswordMessage(bytes: ByteArray, nullTerminated: Boolean) {
        val len = 4 + bytes.size + if (nullTerminated) 1 else 0
        output.writeByte('p'.code)
        output.writeInt(len)
        output.write(bytes)
        if (nullTerminated) output.writeByte(0)
        output.flush()
    }

    private fun sendTypedMessage(type: Char, payload: ByteArray) {
        output.writeByte(type.code)
        output.writeInt(4 + payload.size)
        if (payload.isNotEmpty()) output.write(payload)
    }

    private fun readBackendMessage(): Pair<Char, ByteArray> {
        val typeInt = input.read()
        if (typeInt == -1) throw EOFException("انقطع الاتصال بخادم PostgreSQL")
        val length = input.readInt()
        val bodyLength = (length - 4).coerceAtLeast(0)
        val payload = ByteArray(bodyLength)
        if (bodyLength > 0) {
            input.readFully(payload)
        }
        return typeInt.toChar() to payload
    }

    private fun writeCString(dos: DataOutputStream, text: String) {
        dos.write(text.toByteArray(StandardCharsets.UTF_8))
        dos.writeByte(0)
    }

    private fun readCString(dis: DataInputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = dis.readByte().toInt()
            if (b == 0) break
            buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun readCStringList(payload: ByteArray, offset: Int): List<String> {
        val result = mutableListOf<String>()
        var i = offset
        while (i < payload.size) {
            if (payload[i].toInt() == 0) break
            val start = i
            while (i < payload.size && payload[i].toInt() != 0) i++
            result.add(String(payload, start, i - start, StandardCharsets.UTF_8))
            i++
        }
        return result
    }

    private fun parseErrorResponse(payload: ByteArray): PostgresException {
        var message = "خطأ في خادم قاعدة البيانات PostgreSQL"
        var code: String? = null
        var idx = 0
        while (idx < payload.size) {
            val fieldType = payload[idx].toInt().toChar()
            idx++
            if (fieldType == '\u0000') break
            val start = idx
            while (idx < payload.size && payload[idx].toInt() != 0) idx++
            val value = String(payload, start, idx - start, StandardCharsets.UTF_8)
            idx++
            when (fieldType) {
                'M' -> message = value
                'C' -> code = value
            }
        }
        return PostgresException(message, code)
    }

    private fun computePostgresMd5(user: String, password: String, salt: ByteArray): String {
        val inner = md5Hex((password + user).toByteArray(StandardCharsets.UTF_8))
        val innerBytes = inner.toByteArray(StandardCharsets.UTF_8)
        val combined = ByteArray(innerBytes.size + salt.size)
        System.arraycopy(innerBytes, 0, combined, 0, innerBytes.size)
        System.arraycopy(salt, 0, combined, innerBytes.size, salt.size)
        return "md5" + md5Hex(combined)
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isIpAddress(host: String): Boolean {
        return host.contains(':') || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
    }
}

private class ScramClientState(private val password: String) {
    private val clientNonce: String
    private var clientFirstBare: String = ""

    init {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        clientNonce = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    fun createClientFirstMessage(): String {
        clientFirstBare = "n=,r=$clientNonce"
        return "n,,$clientFirstBare"
    }

    fun processServerFirstMessage(serverFirst: String): String {
        val attrs = mutableMapOf<String, String>()
        for (part in serverFirst.split(',')) {
            val eq = part.indexOf('=')
            if (eq > 0) {
                attrs[part.substring(0, eq)] = part.substring(eq + 1)
            }
        }
        val serverNonce = attrs["r"] ?: throw PostgresException("SCRAM: missing nonce")
        if (!serverNonce.startsWith(clientNonce)) {
            throw PostgresException("SCRAM: invalid server nonce")
        }
        val saltB64 = attrs["s"] ?: throw PostgresException("SCRAM: missing salt")
        val iterations = attrs["i"]?.toIntOrNull() ?: throw PostgresException("SCRAM: invalid iterations")
        val salt = android.util.Base64.decode(saltB64, android.util.Base64.DEFAULT)

        val saltedPassword = pbkdf2HmacSha256(password.toByteArray(StandardCharsets.UTF_8), salt, iterations)
        val clientKey = hmacSha256(saltedPassword, "Client Key".toByteArray(StandardCharsets.UTF_8))
        val storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey)

        val clientFinalWithoutProof = "c=biws,r=$serverNonce"
        val authMessage = "$clientFirstBare,$serverFirst,$clientFinalWithoutProof"
        val clientSignature = hmacSha256(storedKey, authMessage.toByteArray(StandardCharsets.UTF_8))

        val proof = ByteArray(clientKey.size)
        for (i in clientKey.indices) {
            proof[i] = (clientKey[i].toInt() xor clientSignature[i].toInt()).toByte()
        }
        val proofB64 = android.util.Base64.encodeToString(proof, android.util.Base64.NO_WRAP)
        return "$clientFinalWithoutProof,p=$proofB64"
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val u1Input = ByteBuffer.allocate(salt.size + 4).put(salt).putInt(1).array()
        var u = mac.doFinal(u1Input)
        val result = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }
}
