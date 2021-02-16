package net.corda.samples.example

import com.fasterxml.jackson.databind.ObjectMapper
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.utilities.NetworkHostAndPort.Companion.parse
import net.corda.core.utilities.loggerFor
import net.corda.samples.example.flows.*
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.*
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.serialization.collections.ConstructableArrayList
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.lang.reflect.Constructor
import java.math.BigDecimal
import java.security.PublicKey
import java.util.*
import java.util.concurrent.TimeUnit

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

        // If something is missing from here that's because they also missed at in the documentation:
        // https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html
        val exoticTypes: Map<String, Class<*>> = mapOf(

            "byte" to Byte::class.java,
            "char" to Char::class.java,
            "int" to Int::class.java,
            "short" to Short::class.java,
            "long" to Long::class.java,
            "float" to Float::class.java,
            "double" to Double::class.java,
            "boolean" to Boolean::class.java,

            "byte[]" to ByteArray::class.java,
            "char[]" to CharArray::class.java,
            "int[]" to IntArray::class.java,
            "short[]" to ShortArray::class.java,
            "long[]" to LongArray::class.java,
            "float[]" to FloatArray::class.java,
            "double[]" to DoubleArray::class.java,
            "boolean[]" to BooleanArray::class.java
        )
    }

    fun getOrInferType(jvmObject: JvmObject): Class<*> {
        Objects.requireNonNull(jvmObject, "jvmObject must not be null for its type to be inferred.")

        return if (exoticTypes.containsKey(jvmObject.jvmType.fqClassName)) {
            exoticTypes.getOrElse(
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
            val constructorArgs: Array<Any?> = jvmObject.jvmCtorArgs.map { x -> instantiate(x) }.toTypedArray()

            if (List::class.java.isAssignableFrom(clazz)) {
                return listOf(*constructorArgs)
            } else if (Array<Any>::class.java.isAssignableFrom(clazz)) {
                // TODO verify that this actually works and also
                // if we need it at all since we already have lists covered
                return arrayOf(*constructorArgs)
            }
            val constructorArgTypes: List<Class<*>> = jvmObject.jvmCtorArgs.map { x -> getOrInferType(x) }
            val constructor: Constructor<*>
            try {
                constructor = clazz.constructors
                    .filter { c -> c.parameterCount == constructorArgTypes.size }
                    .single { c ->
                        c.parameterTypes
                            .mapIndexed { index, clazz -> clazz.isAssignableFrom(constructorArgTypes[index]) }
                            .all { x -> x }
                    }
            } catch (ex: NoSuchElementException) {
                val argTypes = jvmObject.jvmCtorArgs
                    .map { x -> x.jvmType.fqClassName }
                    .joinToString(",")
                val className = jvmObject.jvmType.fqClassName
                throw RuntimeException("Cannot find matching constructor ${className}(${argTypes})")
            }

            logger.info("Constructor=${constructor}")
            constructorArgs.forEachIndexed { index, it -> logger.info("Constructor ARGS: #${index} -> ${it}") }
            val instance = constructor.newInstance(*constructorArgs)
            logger.info("Instantiated REFERENCE OK {}", instance)
            return instance
        } else if (jvmObject.jvmTypeKind == JvmTypeKind.pRIMITIVE) {
            logger.info("Instantiated PRIMITIVE OK {}", jvmObject.primitiveValue)
            return jvmObject.primitiveValue
        } else {
            throw IllegalArgumentException("Unknown jvmObject.jvmTypeKind (${jvmObject.jvmTypeKind})")
        }
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

        // Example #2, here we print the PartyB's node info
        val me = proxy.nodeInfo().legalIdentities.first().name
        println("\n-- Here is the node info of the node that the client connected to --")
        logger.info("{}", me)

        // Example #3 We invoke a flow dynamically from the HTTP request

        val partyA = proxy.nodeInfo().legalIdentities.single { p -> p.name.organisation.contains("PartyA") }

        val mapper = ObjectMapper()
        val writer = mapper.writerWithDefaultPrettyPrinter()

        val nodeB = nodes.single { n -> n.legalIdentities.any { li -> li.name.organisation.contains("PartyB") } }
        val partyB = nodeB.legalIdentities.first()

        try {
            val algorithm = JvmObject(
                jvmTypeKind = JvmTypeKind.pRIMITIVE,
                primitiveValue = partyB.owningKey.algorithm,
                jvmType = JvmType(
                    fqClassName = java.lang.String::class.java.name
                )
            )

            val format = JvmObject(
                jvmTypeKind = JvmTypeKind.pRIMITIVE,
                primitiveValue = partyB.owningKey.format,
                jvmType = JvmType(
                    fqClassName = java.lang.String::class.java.name
                )
            )

            val encoded = JvmObject(
                jvmTypeKind = JvmTypeKind.pRIMITIVE,
                primitiveValue = partyB.owningKey.encoded,
                jvmType = JvmType(
                    fqClassName = kotlin.ByteArray::class.java.name
                )
            )

            val publicKey = JvmObject(
                jvmTypeKind = JvmTypeKind.rEFERENCE,
                jvmCtorArgs = listOf(algorithm, format, encoded),
                jvmType = JvmType(fqClassName = PublicKeyImpl::class.java.name)
            )

            val cordaX500Name = JvmObject(
                jvmTypeKind = JvmTypeKind.rEFERENCE,
                jvmType = JvmType(fqClassName = CordaX500Name::class.java.name),
                jvmCtorArgs = listOf(
                    JvmObject(
                        jvmTypeKind = JvmTypeKind.pRIMITIVE,
                        primitiveValue = partyB.name.organisation,
                        jvmType = JvmType(
                            fqClassName = java.lang.String::class.java.name
                        )
                    ),
                    JvmObject(
                        jvmTypeKind = JvmTypeKind.pRIMITIVE,
                        primitiveValue = partyB.name.locality,
                        jvmType = JvmType(
                            fqClassName = java.lang.String::class.java.name
                        )
                    ),
                    JvmObject(
                        jvmTypeKind = JvmTypeKind.pRIMITIVE,
                        primitiveValue = partyB.name.country,
                        jvmType = JvmType(
                            fqClassName = java.lang.String::class.java.name
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
                        jvmType = JvmType(fqClassName = Integer::class.java.name)
                    ),
                    JvmObject(
                        JvmTypeKind.rEFERENCE,
                        jvmCtorArgs = listOf(cordaX500Name, publicKey),
                        jvmType = JvmType(fqClassName = Party::class.java.name)
                    )
                )
            )
            logger.info("Req1={}", writer.writeValueAsString(req))

            @Suppress("UNCHECKED_CAST")
            val classFlowLogic = Class.forName(req.flowFullClassName) as Class<out FlowLogic<*>>
            val params = req.params.map { p -> instantiate(p) }.toTypedArray()
            logger.info("params={}", params)
            val flowHandle = proxy.startFlowDynamic(classFlowLogic, *params)

            val timeoutMs: Long = req.timeoutMs?.toLong() ?: 60000
            val flowOut = flowHandle.returnValue.get(timeoutMs, TimeUnit.MILLISECONDS)
            logger.info("flowOut={}", flowOut)
//            val res = InvokeContractV1Response(callOutput = mapOf("result" to flowOut!!))
//            mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
//            val json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(res)
//            logger.info("Response JSON = {}", json);
        } catch (ex: Throwable) {
            throw ex
        }

        try {
            val req = InvokeContractV1Request(
                signingCredential = "fake-signing-credential",
                flowFullClassName = DummyFlow::class.java.name,
                params = listOf(
                    JvmObject(
                        JvmTypeKind.pRIMITIVE,
                        JvmType(
                            fqClassName = Integer::class.java.name
                        ),
                        primitiveValue = 42
                    )
                ),
                cordappName = "fake-cordapp-name",
                timeoutMs = BigDecimal.valueOf(60000)
            )
            logger.info("Req2={}", writer.writeValueAsString(req))

            @Suppress("UNCHECKED_CAST")
            val flowLogicClass = Class.forName(req.flowFullClassName) as Class<out FlowLogic<*>>
            val params = req.params.map { p -> instantiate(p) }.toTypedArray()
            logger.info("params={}", params)
            val flowHandle = proxy.startFlowDynamic(flowLogicClass, *params)
            val timeoutMs: Long = req.timeoutMs?.toLong() ?: 60000
            val flowOut = flowHandle.returnValue.get(timeoutMs, TimeUnit.MILLISECONDS)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        try {
            val req = InvokeContractV1Request(
                signingCredential = "fake-signing-credential",
                flowFullClassName = MultipleNestedGenericsFlow::class.java.name,
                params = listOf(
                    JvmObject(
                        JvmTypeKind.rEFERENCE,
                        JvmType(
                            fqClassName = Tractor::class.java.name
                        ),
                        jvmCtorArgs = listOf(
                            JvmObject(
                                JvmTypeKind.pRIMITIVE,
                                JvmType(fqClassName = String::class.java.name),
                                primitiveValue = "MyCoolTractor12345"
                            ),
                            JvmObject(
                                jvmTypeKind = JvmTypeKind.rEFERENCE,
                                jvmType = JvmType(
                                    fqClassName = ConstructableArrayList::class.java.name
                                ),
                                jvmCtorArgs = listOf(
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.rEFERENCE,
                                        jvmType = JvmType(
                                            fqClassName = SolidWheel::class.java.name
                                        ),
                                        jvmCtorArgs = listOf(
                                            JvmObject(
                                                jvmTypeKind = JvmTypeKind.rEFERENCE,
                                                jvmType = JvmType(fqClassName = TitaniumRim::class.java.name),
                                                jvmCtorArgs = listOf(
                                                    JvmObject(
                                                        jvmTypeKind = JvmTypeKind.pRIMITIVE,
                                                        primitiveValue = "Blue",
                                                        jvmType = JvmType(
                                                            fqClassName = String::class.java.name
                                                        )
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            ),
                            JvmObject(
                                jvmTypeKind = JvmTypeKind.rEFERENCE,
                                jvmType = JvmType(fqClassName = CombustionEngine::class.java.name),
                                jvmCtorArgs = listOf(
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.pRIMITIVE,
                                        jvmType = JvmType(fqClassName = Int::class.java.name),
                                        primitiveValue = 500000 // very strong tractor engine
                                    )
                                )
                            )
                        )
                    )
                ),
                cordappName = "fake-cordapp-name",
                timeoutMs = BigDecimal.valueOf(60000)
            )
            logger.info("Req3={}", writer.writeValueAsString(req))

            @Suppress("UNCHECKED_CAST")
            val flowLogicClass = Class.forName(req.flowFullClassName) as Class<out FlowLogic<*>>
            val params = req.params.map { p -> instantiate(p) }.toTypedArray()
            logger.info("params={}", params)
            val flowHandle = proxy.startFlowDynamic(flowLogicClass, *params)
            val timeoutMs: Long = req.timeoutMs?.toLong() ?: 60000
            val flowOut = flowHandle.returnValue.get(timeoutMs, TimeUnit.MILLISECONDS)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        //Close the client connection
        clientConnection.close()
    }
}