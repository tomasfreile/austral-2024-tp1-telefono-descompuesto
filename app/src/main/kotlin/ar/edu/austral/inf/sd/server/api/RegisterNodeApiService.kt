package ar.edu.austral.inf.sd.server.api

import ar.edu.austral.inf.sd.server.model.RegisterResponse

interface RegisterNodeApiService {

    /**
     * POST /register-node
     * Registra un nuevo nodo
     *
     * @param host  (optional)
     * @param port  (optional)
     * @param uuid  (optional)
     * @param salt  (optional)
     * @param name  (optional)
     * @return Todo bien (status code 200)
     * @see RegisterNodeApi#registerNode
     */
    fun registerNode(host: kotlin.String?, port: kotlin.Int?, uuid: java.util.UUID?, salt: kotlin.String?, name: kotlin.String?): RegisterResponse
}
