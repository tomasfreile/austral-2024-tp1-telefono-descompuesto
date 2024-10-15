package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.ReconfigureApiService
import ar.edu.austral.inf.sd.server.api.UnregisterNodeApiService
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponse
import ar.edu.austral.inf.sd.server.model.Signature
import ar.edu.austral.inf.sd.server.model.Signatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.client.postForEntity
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.system.exitProcess

data class NodeInfo(
    val name: String,
    val host: String,
    val port: Int,
    val uuid: UUID,
    val salt: String,
)

@Component
class ApiServicesImpl @Autowired constructor(
    private val restTemplate: RestTemplate
): RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""
    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    @Value("\${server.timeout:30}")
    private val timeoutInSeconds: Int = 30
    @Value("\${server.host:localhost}")
    private val myServerHost: String = "localhost"

    private val nodes: MutableList<NodeInfo> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private var myUUID=UUID.randomUUID()
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp = 0

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): ResponseEntity<RegisterResponse> {
        // Validar si los parámetros son nulos
        if (host == null || port == null || uuid == null || salt == null || name == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request: missing required parameters")
        }

        // Verificar si ya existe un nodo con el mismo UUID pero una clave (salt) diferente
        if (nodes.any { it.uuid == uuid && it.salt != salt }) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "UUID already exists but the salt does not match")
        }

        // Verificar si el nodo ya está registrado con el mismo UUID y salt
        val existingNode = nodes.find { it.uuid == uuid && it.salt == salt }
        if (existingNode != null) {
            println("Node already registered: $host:$port")
            val nextNodeIndex = nodes.indexOf(existingNode) - 1
            val nextNode = nodes[nextNodeIndex]
            return ResponseEntity(RegisterResponse(nextNode.host, nextNode.port, timeoutInSeconds, xGameTimestamp), HttpStatus.ACCEPTED)
        }

        xGameTimestamp += 1

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(myServerHost, myServerPort, timeoutInSeconds, xGameTimestamp)
            val myNode = NodeInfo(name, host, port, uuid, salt)
            nodes.add(myNode)
            return ResponseEntity(me, HttpStatus.OK)
        } else {
            val lastNode = nodes.last()
            RegisterResponse(lastNode.host, lastNode.port, timeoutInSeconds, xGameTimestamp)
        }
        val node = NodeInfo(name, host, port, uuid, salt)
        nodes.add(node)


        return ResponseEntity(RegisterResponse(nextNode.nextHost, nextNode.nextPort, timeoutInSeconds, xGameTimestamp), HttpStatus.OK)
    }


    // /relay
    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        println("Relaying message: $message")

        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        println("Message received with hash: $receivedHash")

        if (nextNode != null) {
            println("Sending relay message to: ${nextNode!!.nextHost}:${nextNode!!.nextPort}")
            sendRelayMessage(message, receivedContentType, nextNode!!, signatures, xGameTimestamp!!)
        } else {
            // me llego algo, no lo tengo que pasar
            println("No next node, processing message locally.")
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
        val url = "http://$registerHost:$registerPort/register-node?host=$myServerHost&port=$myServerPort&uuid=$myUUID&salt=$salt&name=$myServerName"

        try {
            val response = restTemplate.postForEntity<RegisterResponse>(url)
            val registeredNode = response.body!!
            xGameTimestamp = registeredNode.xGameTimestamp
            println("my xGameTimestamp is $xGameTimestamp")
            println("my uuid is $myUUID")
            println("my salt is $salt")
            nextNode = with(registeredNode) {
                RegisterResponse(
                    nextHost, nextPort,
                    registeredNode.timeout, registeredNode.xGameTimestamp
                )
            }
            println("Registered to server $registerHost:$registerPort")
        } catch (e: Exception) {
            println("Error during registration: ${e.message}")
            exitProcess(1)
        }


    }

    // /play
    override fun sendMessage(body: String): PlayResponse {
        println("Sending message: $body")

        if (nodes.isEmpty()) {
            // inicializamos el primer nodo como yo mismo
            val me = NodeInfo(myServerName, myServerHost, myServerPort, UUID.randomUUID(), salt)
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType

        // Send the message to the last node
        val relayNode = RegisterResponse(nodes.last().host, nodes.last().port, timeoutInSeconds, -1)
        sendRelayMessage(body, contentType, relayNode, Signatures(listOf()), xGameTimestamp)

        if(!resultReady.await(timeoutInSeconds.toLong(), TimeUnit.SECONDS)) {
            resultReady = CountDownLatch(1)
            println("Message relay timed out.")
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout")
        }
        resultReady = CountDownLatch(1)
        return currentMessageResponse.value!!
    }

    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        println("Waiting for the play to finish")
        if (currentMessageWaiting.value != null) {
            if (!resultReady.await(timeoutInSeconds.toLong(), TimeUnit.SECONDS)) {
                resultReady = CountDownLatch(1)
                println("Message relay timed out.")
                throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout")
            }
            resultReady = CountDownLatch(1)
        }

        println("The registered nodes are: $nodes")
        val node = nodes.find { it.uuid == uuid && it.salt == salt }
        if (node == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request: node not found")
        }


        val nodeIndex = nodes.indexOf(node)

        if (nodeIndex == nodes.size - 1) {
            // si el nodo es el ultimo, simplemente lo borro
            nodes.removeAt(nodeIndex)
        } else {
            // si no es el ultimo, tengo que reconfigurar el nodo anterior
            val previousNode = nodes[nodeIndex + 1]
            val myNextNode = nodes[nodeIndex - 1]
            val reconfigureUrl =
                "http://${previousNode.host}:${previousNode.port}/reconfigure?uuid=${previousNode.uuid}&salt=${previousNode.salt}&nextHost=${myNextNode.host}&nextPort=${myNextNode.port}"

            val httpHeaders = HttpHeaders().apply {
                add("X-Game-Timestamp", xGameTimestamp.toString())
            }

            try {
                println("Sending unregister message to: ${nextNode!!.nextHost}:${nextNode!!.nextPort}")
                restTemplate.postForEntity<String>(reconfigureUrl, HttpEntity(null, httpHeaders))
                nodes.removeAt(nodeIndex)
            } catch (e: Exception) {
                println("Error during unregister: ${e.message}")
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Error during unregister to node: ${previousNode.host}:${previousNode.port}"
                )
            }
        }
        return "Unregistered node successfully"


    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        // valido si los parametros son correctos
        if (!(uuid == myUUID && salt == this.salt)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request: node not found")
        }
        // actualizo el nodo siguiente
        this.nextNode = RegisterResponse(nextHost!!, nextPort!!, timeoutInSeconds, xGameTimestamp!!)
        return "Reconfigured node successfully"
    }


    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures,
        timestamp: Int
    ) {

        // if timestamp is <= than the current one, return 400
        if (timestamp < this.xGameTimestamp) {
            println("Invalid timestamp: $xGameTimestamp")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid timestamp")
        }


        val mySignature = clientSign(body, contentType)
        val newSignatures: Signatures = Signatures(signatures.items + mySignature)

        val nextUrl = "http://${relayNode.nextHost}:${relayNode.nextPort}/relay"
        println("Sending message to: $nextUrl")


        // Create headers
        val requestHeaders = HttpHeaders()
        requestHeaders.contentType = MediaType.MULTIPART_FORM_DATA
        requestHeaders["X-Game-Timestamp"] = xGameTimestamp.toString()

        val messageHeaders = HttpHeaders().apply { setContentType(MediaType.parseMediaType(contentType)) }
        val messagePart = HttpEntity(body, messageHeaders)

        val signatureHeaders = HttpHeaders().apply { setContentType(MediaType.APPLICATION_JSON) }
        val signaturesPart = HttpEntity(newSignatures, signatureHeaders)


        // Create the multipart body
        val parts = LinkedMultiValueMap<String, Any>()
        parts.add("message", messagePart)
        parts.add("signatures", signaturesPart)

        val requestEntity = HttpEntity(parts, requestHeaders)

        try {
            val response = restTemplate.postForEntity<Map<String, Any>>(nextUrl, requestEntity)
            println("Response from $nextUrl: ${response.body}")
        } catch (e: Exception) {
            println("Error during relay: ${e.message}")
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Error during relay to node: ${relayNode.nextHost}:${relayNode.nextPort}")
        }

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

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}