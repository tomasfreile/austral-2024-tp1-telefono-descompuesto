package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponse
import ar.edu.austral.inf.sd.server.model.Signature
import ar.edu.austral.inf.sd.server.model.Signatures
import org.springframework.http.HttpHeaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.postForEntity
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@Component
class ApiServicesImpl: RegisterNodeApiService, RelayApiService, PlayApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""
    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    private val nodes: MutableList<RegisterResponse> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = newSalt()
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private val restTemplate = RestTemplate()

    override fun registerNode(host: String?, port: Int?, name: String?): RegisterResponse {

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, "", "")
            nodes.add(me)
            me
        } else {
            nodes.last()
        }
        val uuid = UUID.randomUUID().toString()
        val node = RegisterResponse(host!!, port!!, uuid, newSalt())
        nodes.add(node)

        return RegisterResponse(nextNode.nextHost, nextNode.nextPort, uuid, newSalt())
    }

    override fun relayMessage(message: String, signatures: Signatures): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            // Soy un relé. busco el siguiente y lo mando
            sendRelayMessage(message, receivedContentType, nextNode!!, signatures)
        } else {
            // me llego algo, no lo tengo que pasar
            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
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

    override fun sendMessage(body: String): PlayResponse {
        if (nodes.isEmpty()) {
            // inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, "", "")
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        sendRelayMessage(body, contentType, nodes.last(), Signatures(listOf()))
        resultReady.await()
        resultReady = CountDownLatch(1)
        return currentMessageResponse.value!!
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON

        val response = restTemplate.postForEntity<RegisterResponse>(
            "http://$registerHost:$registerPort/register-node?host=$myServerName&port=$myServerPort&name=$myServerName",
            headers
        )

        val registerNodeResponse: RegisterResponse = response.body ?: throw BadRequestException("No response from server")
        println("nextNode = $registerNodeResponse")

        nextNode = with(registerNodeResponse) { RegisterResponse(nextHost, nextPort, uuid, hash) }
    }

    private fun sendRelayMessage(body: String, contentType: String, relayNode: RegisterResponse, signatures: Signatures) {
        val signature = clientSign(body, contentType)
        val updatedSignatures = Signatures(signatures.items + signature)

        // Create the message entity
        val messageHeaders = HttpHeaders()
        messageHeaders.contentType = MediaType.APPLICATION_JSON

        val messageEntity = HttpEntity(body, messageHeaders)

        // Create the multipart request
        val multiPartBody: MultiValueMap<String, Any> = LinkedMultiValueMap()
        multiPartBody.add("message", messageEntity)
        multiPartBody.add("signatures", updatedSignatures)

        // Create the request entity
        val httpHeaders = HttpHeaders()
        httpHeaders.contentType = MediaType.MULTIPART_FORM_DATA

        val requestEntity = HttpEntity(multiPartBody, httpHeaders)

        val url = "http://${relayNode.nextHost}:${relayNode.nextPort}/relay"

        // Send the request
        restTemplate.postForEntity(url, requestEntity, Signature::class.java)
    }

    private fun clientSign(message: String, contentType: String): Signature {
        val nodeHash = nextNode?.hash ?: salt
        val receivedHash = doHash(message.encodeToByteArray(), nodeHash)
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

    private fun doHash(body: ByteArray, salt: String):  String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}