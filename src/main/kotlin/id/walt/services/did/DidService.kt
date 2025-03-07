package id.walt.services.did

import com.beust.klaxon.Klaxon
import id.walt.crypto.*
import id.walt.crypto.KeyAlgorithm.ECDSA_Secp256k1
import id.walt.crypto.KeyAlgorithm.EdDSA_Ed25519
import id.walt.model.*
import id.walt.services.WaltIdServices
import id.walt.services.context.ContextManager
import id.walt.services.crypto.CryptoService
import id.walt.services.hkvstore.HKVKey
import id.walt.services.key.KeyService
import id.walt.services.vc.JsonLdCredentialService
import id.walt.signatory.ProofConfig
import io.ktor.client.features.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.bouncycastle.asn1.ASN1BitString
import org.bouncycastle.asn1.ASN1Sequence
import java.util.*

private val log = KotlinLogging.logger {}

/**
 * W3C Decentralized Identity Service
 */
object DidService {

    sealed class DidOptions
    data class DidWebOptions(val domain: String?, val path: String? = null) : DidOptions()
    data class DidEbsiOptions(val addEidasKey: Boolean) : DidOptions()


    private val credentialService = JsonLdCredentialService.getService()
    private val cryptoService = CryptoService.getService()
    private val keyService = KeyService.getService()

    // Public methods

    fun create(method: DidMethod, keyAlias: String? = null, options: DidOptions? = null): String {
        val didUrl = when (method) {
            DidMethod.key -> createDidKey(keyAlias)
            DidMethod.web -> createDidWeb(keyAlias, options?.let { it as DidWebOptions } ?: DidWebOptions("walt.id", UUID.randomUUID().toString()))
            DidMethod.ebsi -> createDidEbsi(keyAlias, options as? DidEbsiOptions)
            else -> throw Exception("DID method $method not supported")
        }

        return didUrl
    }

    fun resolve(did: String): Did = resolve(DidUrl.from(did))
    fun resolve(didUrl: DidUrl): Did {
        return when (didUrl.method) {
            DidMethod.key.name -> resolveDidKey(didUrl)
            DidMethod.web.name -> resolveDidWebDummy(didUrl)
            else -> TODO("did:${didUrl.method} not implemented yet")
        }
    }

    fun load(did: String): Did = load(DidUrl.from(did))
    fun load(didUrl: DidUrl): Did {
        return when (didUrl.method) {
            DidMethod.key.name -> resolveDidKey(didUrl)
            DidMethod.web.name -> resolveDidWebDummy(didUrl)
            else -> TODO("did:${didUrl.method} not implemented yet")
        }
    }

    fun resolveDidEbsiRaw(did: String): String = runBlocking {
        log.debug { "Resolving DID $did" }

        val didDoc = WaltIdServices.http.get<String>("https://api.preprod.ebsi.eu/did-registry/v2/identifiers/$did")

        log.debug { didDoc }

        return@runBlocking didDoc
    }

    fun resolveDidEbsi(did: String): DidEbsi = resolveDidEbsi(DidUrl.from(did))
    fun resolveDidEbsi(didUrl: DidUrl): DidEbsi = runBlocking {

        log.debug { "Resolving DID ${didUrl.did}..." }

        var didDoc: String
        var lastEx: ClientRequestException? = null

        for (i in 1..5) {
            try {
                log.debug { "Resolving did:ebsi at: https://api.preprod.ebsi.eu/did-registry/v2/identifiers/${didUrl.did}" }
                didDoc = WaltIdServices.http.get("https://api.preprod.ebsi.eu/did-registry/v2/identifiers/${didUrl.did}")
                log.debug { "Result: $didDoc" }
                return@runBlocking Did.decode(didDoc)!! as DidEbsi
            } catch (e: ClientRequestException) {
                log.debug { "Resolving did ebsi failed: fail $i" }
                Thread.sleep(1000)
                lastEx = e
            }
        }
        log.debug { "Could not resolve did ebsi!" }
        throw lastEx ?: Exception("Could not resolve did ebsi!")
    }

    fun loadDidEbsi(did: String): DidEbsi = loadDidEbsi(DidUrl.from(did))
    fun loadDidEbsi(didUrl: DidUrl): DidEbsi = Did.decode(loadDid(didUrl.did)!!)!! as DidEbsi

    fun updateDidEbsi(did: DidEbsi) = storeDid(did.id, did.encode())
    // Private methods

    private fun createDidEbsi(keyAlias: String?, didEbsiOptions: DidEbsiOptions?): String {
        val keyId = keyAlias?.let { KeyId(it) } ?: cryptoService.generateKey(EdDSA_Ed25519)
        val key = ContextManager.keyStore.load(keyId.id)

        // Created identifier
        val didUrlStr = DidUrl.generateDidEbsiV2DidUrl().did
        ContextManager.keyStore.addAlias(keyId, didUrlStr)

        val kid = didUrlStr + "#" + key.keyId
        ContextManager.keyStore.addAlias(keyId, kid)

        val keyType = when (key.algorithm) {
            EdDSA_Ed25519 -> "Ed25519VerificationKey2018"
            ECDSA_Secp256k1 -> "Secp256k1VerificationKey2018"
        }
        val publicKeyJwk = Klaxon().parse<Jwk>(keyService.toJwk(kid).toPublicJWK().toString())

        val verificationMethods = mutableListOf(
            VerificationMethod(kid, keyType, didUrlStr, null, null, publicKeyJwk),
        )

        val did = DidEbsi(
            listOf(DID_CONTEXT_URL), // TODO Context not working "https://ebsi.org/ns/did/v1"
            didUrlStr,
            verificationMethods,
            listOf(kid)
        )
        val ebsiDid = did.encode()

//        val ebsiDid = if (key.algorithm == EdDSA_Ed25519) {
//            val pubKeyBytes = key.getPublicKey().encoded
//            val pubPrim = ASN1Sequence.fromByteArray(pubKeyBytes) as ASN1Sequence
//            val edPublicKey = (pubPrim.getObjectAt(1) as ASN1BitString).octets
//            // Create doc
//            val ebsiDidBody = ebsiDid(DidUrl.from(didUrlStr), edPublicKey)
//
//            val ebsiDidBodyStr = Klaxon().toJsonString(ebsiDidBody)
//
//            keyStore.addAlias(keyId, ebsiDidBody.verificationMethod!!.get(0)!!.id)
//
//            // Create proof
//            val verificationMethod = ebsiDidBody.verificationMethod?.get(0)?.id
//            signDid(didUrlStr, verificationMethod!!, ebsiDidBodyStr)
//        } else {
//            val kid = "$didUrlStr#key-1"
//            keyStore.addAlias(keyId, kid)
//            val publicKeyJwk = Klaxon().parse<Jwk>(KeyService.toJwk(kid).toPublicJWK().toString())
//            val verificationMethods = mutableListOf(
//                VerificationMethod(kid, "Secp256k1VerificationKey2018", didUrlStr, null, null, publicKeyJwk),
//            )
//
//            val did = DidEbsi(
//                listOf("https://w3id.org/did/v1"), // TODO Context not working "https://ebsi.org/ns/did/v1"
//                didUrlStr,
//                verificationMethods,
//                listOf("$didUrlStr#key-1")
//            )
//            Klaxon().toJsonString(did)
//        }

        // Store DID
        storeDid(didUrlStr, ebsiDid)

        return didUrlStr
    }

    private fun createDidKey(keyAlias: String?): String {
        val keyId = keyAlias?.let { KeyId(it) } ?: cryptoService.generateKey(EdDSA_Ed25519)
        val key = ContextManager.keyStore.load(keyId.id)

        if (key.algorithm != EdDSA_Ed25519)
            throw Exception("DID KEY can only be created with an EdDSA Ed25519 key.")

        val pubPrim = ASN1Sequence.fromByteArray(key.getPublicKey().encoded) as ASN1Sequence
        val x = (pubPrim.getObjectAt(1) as ASN1BitString).octets

        val identifier = convertEd25519PublicKeyToMultiBase58Btc(x)
        val didUrl = "did:key:$identifier"

        ContextManager.keyStore.addAlias(keyId, didUrl)

        resolveAndStore(didUrl)

        return didUrl
    }

    private fun createDidWeb(keyAlias: String?, options: DidWebOptions?): String {

        val key = keyAlias?.let { ContextManager.keyStore.load(it) } ?: cryptoService.generateKey(EdDSA_Ed25519).let { ContextManager.keyStore.load(it.id) }

        val domain = options?.domain ?: throw Exception("Missing 'domain' parameter for creating did:web")
        val path = options?.path?.apply { replace("/", ":") }?.let { ":$it" } ?: ""

        val didUrl = DidUrl("web", "$domain$path")

        try {
            ContextManager.keyStore.addAlias(key.keyId, didUrl.did)
        } catch (e: Exception) {
            throw Exception("Could not store key alias ${didUrl.did} for key ${key.keyId}", e)
        }

        storeDid(didUrl.did, resolveDidWebDummy(didUrl).toString())

        return didUrl.did
    }

    private fun signDid(issuerDid: String, verificationMethod: String, edDidStr: String): String {
        return credentialService.sign(edDidStr, ProofConfig(issuerDid = issuerDid, issuerVerificationMethod = verificationMethod))
    }

    private fun resolveDidKey(didUrl: DidUrl): Did {
        val pubKey = convertEd25519PublicKeyFromMultibase58Btc(didUrl.identifier)
        return ed25519Did(didUrl, pubKey)
    }

    private fun ebsiDid(didUrl: DidUrl, pubKey: ByteArray): DidEbsi {
        val (dhKeyId, verificationMethods, keyRef) = generateEdParams(pubKey, didUrl)

        // TODO Replace EIDAS dummy certificate with real one
//        {
//            "id": "did:ebsi:2b6a1ee5881158edf133421d63d4b9e5f3ac26d474c#key-3",
//            "type": "EidasVerificationKey2021",
//            "controller": "did:ebsi:2b6a1ee5881158edf133421d63d4b9e5f3ac26d472afcff8",
//            "publicKeyPem": "-----BEGIN.."
//        }

        val eidasKeyId = didUrl.identifier + "#" + UUID.randomUUID().toString().replace("-", "")
        verificationMethods.add(
            VerificationMethod(
                eidasKeyId,
                "EidasVerificationKey2021",
                "publicKeyPem",
                "-----BEGIN.."
            )
        )

        return DidEbsi(
            listOf(DID_CONTEXT_URL), // TODO Context not working "https://ebsi.org/ns/did/v1"
            didUrl.did,
            verificationMethods,
            keyRef,
            keyRef,
            keyRef,
            keyRef,
            listOf(dhKeyId),
            null
        )
    }

    private fun ed25519Did(didUrl: DidUrl, pubKey: ByteArray): Did {
        val (dhKeyId, verificationMethods, keyRef) = generateEdParams(pubKey, didUrl)

        return Did(
            DID_CONTEXT_URL,
            didUrl.did,
            verificationMethods,
            keyRef,
            keyRef,
            keyRef,
            keyRef,
            listOf(dhKeyId),
            null
        )
    }

    private fun generateEdParams(
        pubKey: ByteArray,
        didUrl: DidUrl
    ): Triple<String, MutableList<VerificationMethod>, List<String>> {
        val dhKey = convertPublicKeyEd25519ToCurve25519(pubKey)

        val dhKeyMb = convertX25519PublicKeyToMultiBase58Btc(dhKey)

        val pubKeyId = didUrl.identifier + "#" + didUrl.identifier
        val dhKeyId = didUrl.identifier + "#" + dhKeyMb

        val verificationMethods = mutableListOf(
            VerificationMethod(pubKeyId, "Ed25519VerificationKey2018", didUrl.did, pubKey.encodeBase58()),
            VerificationMethod(dhKeyId, "X25519KeyAgreementKey2019", didUrl.did, dhKey.encodeBase58())
        )

        val keyRef = listOf(pubKeyId)
        return Triple(dhKeyId, verificationMethods, keyRef)
    }

    fun resolveDidWebDummy(didUrl: DidUrl): Did {
        log.warn { "DID WEB implementation is not finalized yet. Use it only for demo purpose." }
        ContextManager.keyStore.getKeyId(didUrl.did).let {
            ContextManager.keyStore.load(it!!).let {
                val pubKeyId = didUrl.identifier + "#key-1"
                val verificationMethods = listOf(
                    VerificationMethod(
                        pubKeyId,
                        "ECDSASecp256k1VerificationKey2018",
                        didUrl.did,
                        "zMIGNAgEAMBAGByqGSM49AgEGBSuBBAAKBHYwdAIBAQQgPI4jGjTGPA3yFJp07jvx"
                    ), // TODO: key encoding
                )
                val keyRef = listOf(pubKeyId)
                return Did(
                    DID_CONTEXT_URL,
                    didUrl.did,
                    verificationMethods,
                    keyRef,
                    keyRef,
                    keyRef,
                    keyRef,
                    null
                )
            }
        }
    }

    fun getAuthenticationMethods(did: String) = when (DidUrl.from(did).method) {
        DidMethod.ebsi.name -> loadDidEbsi(did).authentication
        else -> load(did).authentication
    }

    private fun resolveAndStore(didUrl: String) = storeDid(didUrl, resolve(didUrl).encodePretty())

    private fun storeDid(didUrlStr: String, didDoc: String) =
        ContextManager.hkvStore.put(HKVKey("did", "created", didUrlStr), didDoc)

    private fun loadDid(didUrlStr: String) =
        ContextManager.hkvStore.getAsString(HKVKey("did", "created", didUrlStr))


    fun listDids(): List<String> =
        ContextManager.hkvStore.listChildKeys(HKVKey("did", "created")).map { it.name }.toList()

    fun loadOrResolveAnyDid(didStr: String): Did? {
        log.debug { "Loading or resolving \"$didStr\"..." }
        val url = DidUrl.from(didStr)
        val storedDid = loadDid(didStr)

        log.debug { "loadOrResolve: url=$url, length of stored=${storedDid?.length}" }
        return when (storedDid) {
            null -> when (url.method) {
                DidMethod.key.name -> resolveDidKey(url)
                DidMethod.ebsi.name -> kotlin.runCatching { resolveDidEbsi(didStr) }.getOrNull()
                // TODO: implement did:web
                else -> null
            }?.apply { storeDid(didStr, this.encodePretty()) }
            else -> Did.decode(storedDid)
        }
    }

    fun importKey(didUrl: String) {
        val did = loadOrResolveAnyDid(didUrl) ?: throw Exception("Could not load or resolve $didUrl")

        val verificationMethod = when (did) {
            is DidEbsi -> did.verificationMethod
            is Did -> did.verificationMethod
            else -> throw Exception("Did not supported")
        } ?: throw Exception("Could not import key as no verification method was found")

        verificationMethod.forEach { vm ->
            if (!booleanArrayOf(
                    importJwk(didUrl, vm.publicKeyJwk),
                    importKeyBase58(didUrl, vm.publicKeyBase58),
                    importKeyPem(didUrl, vm.publicKeyPem)
                ).contains(true)
            ) {
                throw Exception("Could not import any key")
            }
        }
    }

    private fun importKeyPem(keyAlias: String, keyPem: String?): Boolean {

        keyPem ?: return false

        // TODO implement

        return false
    }

    private fun importKeyBase58(keyAlias: String, keyBase58: String?): Boolean {

        keyBase58 ?: return false

        // TODO implement

        return false
    }

    private fun importJwk(keyAlias: String, publicKeyJwk: Jwk?): Boolean {

        publicKeyJwk ?: return false

        kotlin.runCatching { KeyService.getService().load(keyAlias) }
            .getOrNull()?.let { throw Exception("Could not import key, as key alias \"$keyAlias\" is already existing.") }

        publicKeyJwk.kid = keyAlias
        log.debug { "Importing key: ${publicKeyJwk.kid}" }
        val keyId = KeyService.getService().importKey(Klaxon().toJsonString(publicKeyJwk))
        ContextManager.keyStore.addAlias(keyId, keyAlias)
        return true
    }

    // TODO: consider the methods below. They might be deprecated!

//    fun resolveDid(did: String): Did = resolveDid(did.DidUrl.toDidUrl())
//
//    fun resolveDid(didUrl: DidUrl): Did {
//        return when (didUrl.method) {
//            "key" -> resolveDidKey(didUrl)
//            "web" -> resolveDidWebDummy(didUrl)
//            else -> TODO("did:${didUrl.method} implemented yet")
//        }
//    }

// TODO: include once working
//    internal fun resolveDidWeb(didUrl: DidUrl): DidWeb {
//        val domain = didUrl.identifier
//        val didUrl = "https://${domain}/.well-known/did.json" // FIXME: didUrl argument is ignored?
//        log.debug { "Resolving did:web for domain $domain at: $didUrl" }
//        val didWebStr = URL(didUrl).readText()
//        log.debug { "did:web resolved:\n$didWebStr" }
//        print(didWebStr)
//        val did = Klaxon().parse<DidWeb>(didWebStr)
//        log.debug { "did:web decoded:\n$did" }
//        return did
//    }

//    @Deprecated(message ="use create()")
//    fun createDid(didMethod: String, keys: Keys? = null): String {
//
//        val didKey = if (keys != null) keys else {
//            val keyId = KeyManagementService.generateEd25519KeyPairNimbus() // .generateKeyPair("Ed25519")
//            KeyManagementService.loadKeys(keyId)
//        }!!
//
//        return when (didMethod) {
//            "key" -> createDidKey(didKey)
//            "web" -> createDidWeb(didKey)
//            else -> T//ODO("did creation by method $didMethod not supported yet")
//        }
//    }

//    internal fun createDidKey(didKey: Keys): String {
//
//        val identifier = convertEd25519PublicKeyToMultiBase58Btc(didKey.getPubKey())
//
//        val did = "did:key:$identifier"
//
//        KeyManagementService.addAlias(didKey.keyId, did)
//
//        return did
//    }
//
//    internal fun createDidWeb(didKey: Keys): String {
//        val domain = "walt.id"
//        val username = UUID.randomUUID().toString().replace("-", "")
//        val path = ":user:$username"
//
//        val didUrl = DidUrl("web", "" + domain + path, didKey.keyId)
//
//        KeyManagementService.addAlias(didKey.keyId, didUrl.did)
//
//        return didUrl.did
//    }

}


