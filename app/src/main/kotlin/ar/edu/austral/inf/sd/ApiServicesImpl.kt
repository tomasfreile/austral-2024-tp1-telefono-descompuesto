package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.api.ReconfigureApiService
import ar.edu.austral.inf.sd.server.api.UnregisterNodeApiService
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponse
import ar.edu.austral.inf.sd.server.model.Signature
import ar.edu.austral.inf.sd.server.model.Signatures
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@Component
class ApiServicesImpl @Autowired constructor(
    private val restTemplate: RestTemplate
): RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""

    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    private val nodes: MutableList<RegisterResponse> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp: Int = 0
    private var timeoutInSeconds: Int = 60

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): RegisterResponse {
        // Si hay un dato vacio, devolver 400 (Bad Request)
        if (host == null || port == null || uuid == null || salt == null || name == null) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid request: missing required parameters")
        }

        // Si el UUID ya existe, pero la clave privada es distinta, devolver 401 (Unauthorized)
        if (nodes.any { it.uuid == uuid && it.salt != salt }) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "UUID already exists but the salt does not match")
        }

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, uuid, salt, timeoutInSeconds, xGameTimestamp)
            nodes.add(me)
            me
        } else {
            nodes.last()
        }
        val node = RegisterResponse(host, port, uuid, salt, timeoutInSeconds, xGameTimestamp)
        nodes.add(node)
        xGameTimestamp++

        return RegisterResponse(nextNode.nextHost, nextNode.nextPort, nextNode.uuid, nextNode.salt, timeoutInSeconds, nextNode.xGameTimestamp)
    }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        // if timestamp is <= than the current one, return 400
        if (xGameTimestamp != null && xGameTimestamp <= this.xGameTimestamp) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp")
        }

        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            sendRelayMessage(message, receivedContentType, nextNode!!, signatures)
        } else {
            // me llego algo, no lo tengo que pasar
            if (currentMessageWaiting.value == null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No message waiting")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (receivedHash == current.originalHash) "Success" else "Failure",
                receivedHash = receivedHash,
                receivedLength = receivedLength,
                receivedContentType = receivedContentType,
                signatures = signatures
            )
            currentMessageResponse.update { response }
            resultReady.countDown()
        }
        return Signature(
            name = myServerName,
            hash = receivedHash,
            contentType = receivedContentType,
            contentLength = receivedLength
        )
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        // @ToDo acá tienen que trabajar ustedes
        val registerNodeResponse: RegisterResponse = RegisterResponse(registerHost, registerPort, UUID.randomUUID(), "", timeoutInSeconds, 0)
        println("nextNode = ${registerNodeResponse}")
        nextNode = registerNodeResponse
    }

    // /play
    override suspend fun sendMessage(body: String): PlayResponse {
        if (nodes.isEmpty()) {
            // Inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, UUID.randomUUID(), salt, timeoutInSeconds, xGameTimestamp)
            nodes.add(me)
        }

        // Preparar el nuevo mensaje
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Content type is missing")

        resultReady = CountDownLatch(1)

        try {
            withTimeout(timeoutInSeconds * 1000L) {
                sendRelayMessage(body, contentType, nodes.last(), Signatures(listOf()))
                resultReady.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "La ronda del juego ha excedido el tiempo")
        }

        return currentMessageResponse.value ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error desconocido: no se generó la respuesta")
    }


    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        TODO("Not yet implemented")
    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        TODO("Not yet implemented")
    }


    // /relay
    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures,
    ) {
        val newHash = doHash(body.encodeToByteArray(), salt)
        val newSignatures = signatures.items + Signature(myServerName, newHash , contentType, body.length )

        val nextUrl = "http://${relayNode.nextHost}:${relayNode.nextPort}/relay"

        // Create headers
        val headers = HttpHeaders()
        headers.contentType = MediaType.MULTIPART_FORM_DATA
        headers["X-Game-Timestamp"] = xGameTimestamp.toString()

        // Create the multipart body
        val bodyMap = LinkedMultiValueMap<String, Any>()
        bodyMap.add("message", body)
        bodyMap.add("signatures", newSignatures)

        // Create the request entity
        val requestEntity = HttpEntity(bodyMap, headers)

        try {
            val response: ResponseEntity<String> = restTemplate.exchange(
                nextUrl,
                HttpMethod.POST,
                requestEntity,
                String::class.java
            )
            println("Response: ${response.body}")
        } catch (e: Exception) {
            // 503
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Error al enviar el mensaje")
        }
    }

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), salt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}