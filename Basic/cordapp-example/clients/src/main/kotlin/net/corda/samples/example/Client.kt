package net.corda.samples.example

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.NetworkHostAndPort.Companion.parse
import net.corda.core.utilities.loggerFor
import net.corda.samples.example.flows.ExampleFlow
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.InvokeContractV1Request
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.JvmObject
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.JvmType
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.JvmTypeKind
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.security.PublicKey
import java.util.*

data class PublicKeyImpl(
    private val _algorithm: String,
    private val _format: String,
    private val _byteArray: ByteArray
) : PublicKey {
    override fun getAlgorithm(): String {
        return _algorithm
    }

    override fun getFormat(): String {
        return _format
    }

    override fun getEncoded(): ByteArray {
        return _byteArray
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PublicKeyImpl

        if (_algorithm != other._algorithm) return false
        if (_format != other._format) return false
        if (!_byteArray.contentEquals(other._byteArray)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = _algorithm.hashCode()
        result = 31 * result + _format.hashCode()
        result = 31 * result + _byteArray.contentHashCode()
        return result
    }
}

/**
 * Connects to a Corda node via RPC and performs RPC operations on the node.
 *
 * The RPC connection is configured using command line arguments.
 */
fun main(args: Array<String>) = Client().main(args)

private class Client {
    companion object {
        val logger = loggerFor<Client>()
    }

    fun main(args: Array<String>) {
        // Create an RPC connection to the node.
        require(args.size == 3) { "Usage: Client <node address> <rpc username> <rpc password>" }
        val nodeAddress = parse(args[0])
        val rpcUsername = args[1]
        val rpcPassword = args[2]
        val client = CordaRPCClient(nodeAddress)
        val clientConnection = client.start(rpcUsername, rpcPassword)
        val proxy = clientConnection.proxy

        // Interact with the node.
        // Example #1, here we print the nodes on the network.
        val nodes = proxy.networkMapSnapshot()
        println("\n-- Here is the networkMap snapshot --")
        logger.info("{}", nodes)

        // Example #2, here we print the PartyA's node info
        val me = proxy.nodeInfo().legalIdentities.first().name
        println("\n-- Here is the node info of the node that the client connected to --")
        logger.info("{}", me)

        // Example #3 We invoke a flow dynamically from the HTTP request

        val party = proxy.nodeInfo().legalIdentities.first()
        val publicKeyImpl = PublicKeyImpl(party.owningKey.algorithm, party.owningKey.format, party.owningKey.encoded)

        val algorithm = JvmObject(
            jvmTypeKind = JvmTypeKind.pRIMITIVE,
            primitiveValue = party.owningKey.algorithm,
            jvmType = JvmType(
                fqClassName = java.lang.String::class.java.name,
                typeParameters = emptyList()
            )
        )

        val format = JvmObject(
            jvmTypeKind = JvmTypeKind.pRIMITIVE,
            primitiveValue = party.owningKey.format,
            jvmType = JvmType(
                fqClassName = java.lang.String::class.java.name,
                typeParameters = emptyList()
            )
        )

        val encoded = JvmObject(
            jvmTypeKind = JvmTypeKind.pRIMITIVE,
            primitiveValue = party.owningKey.encoded,
            jvmType = JvmType(
                fqClassName = kotlin.ByteArray::class.java.name,
                typeParameters = emptyList()
            )
        )

        val publicKey = JvmObject(
            jvmTypeKind = JvmTypeKind.rEFERENCE,
            jvmCtorArgs = listOf(algorithm, format, encoded),
            jvmType = JvmType(fqClassName = PublicKeyImpl::class.java.name, typeParameters = emptyList())
        )

        val cordaX500Name = JvmObject(
            jvmTypeKind = JvmTypeKind.rEFERENCE,
            jvmType = JvmType(fqClassName = CordaX500Name::class.java.name, typeParameters = emptyList()),
            jvmCtorArgs = listOf(
                JvmObject(
                    jvmTypeKind = JvmTypeKind.pRIMITIVE,
                    primitiveValue = "PartyA",
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name,
                        typeParameters = emptyList()
                    )
                ),
                JvmObject(
                    jvmTypeKind = JvmTypeKind.pRIMITIVE,
                    primitiveValue = "London",
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name,
                        typeParameters = emptyList()
                    )
                ),
                JvmObject(
                    jvmTypeKind = JvmTypeKind.pRIMITIVE,
                    primitiveValue = "GB",
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name,
                        typeParameters = emptyList()
                    )
                )
            )
        )

        val req = InvokeContractV1Request(
            signingCredential = "mySigningCredential",
            flowFullClassName = ExampleFlow.Initiator::class.java.name,
            cordappName = "mycordapp",
            params = listOf(
                JvmObject(
                    JvmTypeKind.pRIMITIVE,
                    primitiveValue = 42,
                    jvmType = JvmType(fqClassName = Integer::class.java.name, typeParameters = emptyList())
                ),
                JvmObject(
                    JvmTypeKind.rEFERENCE,
                    jvmCtorArgs = listOf(cordaX500Name, publicKey),
                    jvmType = JvmType(fqClassName = Party::class.java.name, typeParameters = emptyList())
                )
            )
        )

        try {
            // If something is missing from here that's because they also missed at in the documentation:
            // https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
            val exoticJvmTypes: Map<String, Class<*>> = mapOf(
                "byte[]" to ByteArray::class.java,
                "int[]" to IntArray::class.java,
                "short[]" to ShortArray::class.java,
                "long[]" to LongArray::class.java,
                "char[]" to CharArray::class.java,
                "float[]" to FloatArray::class.java,
                "double[]" to DoubleArray::class.java,
                "boolean[]" to BooleanArray::class.java
            )

            fun getOrInferType(jvmObject: JvmObject): Class<*> {
                Objects.requireNonNull(jvmObject, "jvmObject must not be null for its type to be inferred.")

                return if (exoticJvmTypes.containsKey(jvmObject.jvmType.fqClassName)) {
                    exoticJvmTypes.getOrElse(
                        jvmObject.jvmType.fqClassName,
                        { throw IllegalStateException("Could not locate Class<*> for ${jvmObject.jvmType.fqClassName} Exotic JVM types map must have been modified on a concurrent threat.") })
                } else {
                    Class.forName(jvmObject.jvmType.fqClassName)
                }
            }

            fun instantiate(jvmObject: Any?): Any? {
                logger.info("Instantiating ... JvmObject={}", jvmObject)
                if (jvmObject == null) {
                    return jvmObject
                }
                if (jvmObject !is JvmObject) {
                    return jvmObject
                }

                val clazz = getOrInferType(jvmObject)

                if (jvmObject.jvmTypeKind == JvmTypeKind.rEFERENCE) {
                    if (jvmObject.jvmCtorArgs == null) {
                        throw IllegalArgumentException("jvmObject.jvmCtorArgs cannot be null when jvmObject.jvmTypeKind == JvmTypeKind.rEFERENCE")
                    }
                    if (jvmObject.jvmType.typeParameters.isEmpty()) {
                        val ctorArgs = jvmObject.jvmCtorArgs.map { x -> instantiate(x) }.toTypedArray()
                        val ctorArgTypes: List<Class<*>> = jvmObject.jvmCtorArgs.map { x -> getOrInferType(x) }
                        val ctor = clazz.constructors.filterNotNull()
                            .filter { c -> c.parameterCount == ctorArgTypes.size }
                            .single { c ->
                                c.parameterTypes
                                    .mapIndexed { index, clazz -> clazz.isAssignableFrom(ctorArgTypes[index]) }
                                    .all { x -> x }
                            }
                        val instance = ctor.newInstance(*ctorArgs)
                        logger.info("Instantiated rEFERENCE OK {}", instance)
                        return instance
                    } else {
                        throw RuntimeException("Oops, did not implement generic instantiation just yet. Stay tuned...")
                    }

                } else if (jvmObject.jvmTypeKind == JvmTypeKind.pRIMITIVE) {
                    logger.info("Instantiated pRIMITIVE OK {}", jvmObject.primitiveValue)
                    return jvmObject.primitiveValue
                } else {
                    throw IllegalArgumentException("Unknown jvmObject.jvmTypeKind (${jvmObject.jvmTypeKind})")
                }
            }

            @Suppress("UNCHECKED_CAST")
            val classFlowLogic = Class.forName(req.flowFullClassName) as Class<out FlowLogic<*>>
            val params = req.params.map { p -> instantiate(p) }.toTypedArray()
            logger.info("params={}", params)
            val flowOut = proxy.startFlowDynamic(classFlowLogic, *params)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        try {
            val classFlowLogic = ExampleFlow.Initiator::class.java
            val params = listOf(42, party).toTypedArray()
            logger.info("params={}", params)
            val flowOut = proxy.startFlowDynamic(classFlowLogic, *params)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        try {
            // https://github.com/corda/corda/blob/release/os/4.8/core/src/main/kotlin/net/corda/core/identity/Party.kt
            val classParty = Class.forName("net.corda.core.identity.Party")

            val classX500name = Class.forName("net.corda.core.identity.CordaX500Name")
            val x500nameArgs = listOf<Any>("PartyA", "London", "GB")
            val x500nameArgTypes = listOf<Class<*>>(
                Class.forName("java.lang.String"),
                Class.forName("java.lang.String"),
                Class.forName("java.lang.String")
            )
            classX500name.constructors.forEach { constructor ->
                logger.info(
                    ">>> constructor.parameterCount={}, constructor.parameterTypes={}",
                    constructor.parameterCount,
                    constructor.parameterTypes
                )
            }

            val x500NameCtor = classX500name.constructors.find { constructor ->
                constructor.parameterTypes!!.contentDeepEquals(x500nameArgTypes.toTypedArray())
            }
            logger.info("x500NameCtor={}", x500NameCtor)
            val x500nameFromReq = x500NameCtor!!.newInstance(*x500nameArgs.toTypedArray())
            logger.info("x500nameFromReq={}", x500nameFromReq)

            val partyCtorTypes = arrayOf(
                Class.forName("net.corda.core.identity.CordaX500Name"),
                Class.forName(PublicKeyImpl::class.java.name)
            )
            val partyCtorArgs = arrayOf(x500nameFromReq, publicKeyImpl)

            val partyAFromReqCtor = classParty.constructors.filterNotNull()
                .single { c -> c.parameterTypes.all { pt -> partyCtorTypes.any { pct -> pt.isAssignableFrom(pct) } } }

            val partyAFromReq = partyAFromReqCtor.newInstance(*partyCtorArgs)

            val classFlowLogic = ExampleFlow.Initiator::class.java

            val params = listOf<Any>(42, partyAFromReq).toTypedArray()
            logger.info("party={}", party)
            logger.info("params={}", params)
            // public net.corda.core.identity.Party(net.corda.core.identity.CordaX500Name,java.security.PublicKey)
            val flowOut = proxy.startFlowDynamic(classFlowLogic, *params)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        //Close the client connection
        clientConnection.close()
    }
}