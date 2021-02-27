package net.corda.samples.example

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.CordaRuntimeException
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.FlowProgressHandle
import net.corda.core.utilities.NetworkHostAndPort.Companion.parse
import net.corda.core.utilities.loggerFor
import net.corda.nodeapi.exceptions.DuplicateAttachmentException
import net.corda.samples.example.flows.*
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.model.*
import org.hyperledger.cactus.plugin.ledger.connector.corda.server.serialization.collections.ConstructableArrayList
import org.xeustechnologies.jcl.JarClassLoader
import java.io.File
import java.lang.reflect.Constructor
import java.math.BigDecimal
import java.nio.file.Paths
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

        val jcl: JarClassLoader = JarClassLoader(Client::class.java.classLoader)

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

    fun getOrInferType(fqClassName: String): Class<*> {
        Objects.requireNonNull(fqClassName, "fqClassName must not be null for its type to be inferred.")

        return if (exoticTypes.containsKey(fqClassName)) {
            exoticTypes.getOrElse(
                fqClassName,
                { throw IllegalStateException("Could not locate Class<*> for $fqClassName Exotic JVM types map must have been modified on a concurrent threat.") })
        } else {
            try {
                jcl.loadClass(fqClassName, true)
            } catch (ex: ClassNotFoundException) {
                Class.forName(fqClassName)
            }
        }
    }

    fun instantiate(jvmObject: JvmObject): Any? {
        logger.info("Instantiating ... JvmObject={}", jvmObject)

        val clazz = getOrInferType(jvmObject.jvmType.fqClassName)

        if (jvmObject.jvmTypeKind == JvmTypeKind.REFERENCE) {
            if (jvmObject.jvmCtorArgs == null) {
                throw IllegalArgumentException("jvmObject.jvmCtorArgs cannot be null when jvmObject.jvmTypeKind == JvmTypeKind.REFERENCE")
            }
            val constructorArgs: Array<Any?> = jvmObject.jvmCtorArgs.map { x -> instantiate(x) }.toTypedArray()

            if (List::class.java.isAssignableFrom(clazz)) {
                return listOf(*constructorArgs)
            } else if (Currency::class.java.isAssignableFrom(clazz)) {
                // FIXME introduce a more dynamic/flexible way of handling classes with no public constructors....
                return Currency.getInstance(jvmObject.jvmCtorArgs.first().primitiveValue as String)
            } else if (Array<Any>::class.java.isAssignableFrom(clazz)) {
                // TODO verify that this actually works and also
                // if we need it at all since we already have lists covered
                return arrayOf(*constructorArgs)
            }
            val constructorArgTypes: List<Class<*>> = jvmObject.jvmCtorArgs.map { x -> getOrInferType(x.jvmType.fqClassName) }
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
                val argTypes = jvmObject.jvmCtorArgs.joinToString(",") { x -> x.jvmType.fqClassName }
                val className = jvmObject.jvmType.fqClassName
                val constructorsAsStrings = clazz.constructors
                    .mapIndexed { i, c -> "$className->Constructor#${i + 1}(${c.parameterTypes.joinToString { p -> p.name }})" }
                    .joinToString(" ;; ")
                val targetConstructor = "Cannot find matching constructor for ${className}(${argTypes})"
                val availableConstructors = "Searched among the ${clazz.constructors.size} available constructors: $constructorsAsStrings"
                throw RuntimeException("$targetConstructor --- $availableConstructors")
            }

            logger.info("Constructor=${constructor}")
            constructorArgs.forEachIndexed { index, it -> logger.info("Constructor ARGS: #${index} -> $it") }
            val instance = constructor.newInstance(*constructorArgs)
            logger.info("Instantiated REFERENCE OK {}", instance)
            return instance
        } else if (jvmObject.jvmTypeKind == JvmTypeKind.PRIMITIVE) {
            logger.info("Instantiated PRIMITIVE OK {}", jvmObject.primitiveValue)
            return jvmObject.primitiveValue
        } else {
            throw IllegalArgumentException("Unknown jvmObject.jvmTypeKind (${jvmObject.jvmTypeKind})")
        }
    }

    fun dynamicInvoke(rpc: CordaRPCOps, req: InvokeContractV1Request): InvokeContractV1Response {
        @Suppress("UNCHECKED_CAST")
        val classFlowLogic = getOrInferType(req.flowFullClassName) as Class<out FlowLogic<*>>
        val params = req.params.map { p -> instantiate(p) }.toTypedArray()
        logger.info("params={}", params)

        val flowHandle = when (req.flowInvocationType) {
            FlowInvocationType.TRACKED_FLOW_DYNAMIC -> rpc.startTrackedFlowDynamic(classFlowLogic, *params)
            FlowInvocationType.FLOW_DYNAMIC -> rpc.startFlowDynamic(classFlowLogic, *params)
        }

        val timeoutMs: Long = req.timeoutMs?.toLong() ?: 60000

        val progress: List<String> = when (req.flowInvocationType) {
            FlowInvocationType.TRACKED_FLOW_DYNAMIC -> (flowHandle as FlowProgressHandle<*>)
                .progress
                .toList()
                .toBlocking()
                .first()
            FlowInvocationType.FLOW_DYNAMIC -> emptyList()
        }
        val returnValue = flowHandle.returnValue.get(timeoutMs, TimeUnit.MILLISECONDS)
        val id = flowHandle.id

        logger.info("Progress(${progress.size})={}", progress)
        logger.info("ReturnValue={}", returnValue)
        logger.info("Id=$id")
        return InvokeContractV1Response(id.toString(), progress, returnValue)
    }

    fun main(args: Array<String>) {
        // Create an RPC connection to the node.
        require(args.size == 9) { "Usage: Client <node address> <rpc username> <rpc password>" }

//        val nodeAddressN = parse(args[6])
//        val rpcUsernameN = args[7]
//        val rpcPasswordN = args[8]
//        val clientN = CordaRPCClient(nodeAddressN)
//        val clientConnectionN = clientN.start(rpcUsernameN, rpcPasswordN)
//        val proxyN = clientConnectionN.proxy
//        logger.info("Connection to Node N established OK")

        val nodeAddressA = parse(args[0])
        val rpcUsernameA = args[1]
        val rpcPasswordA = args[2]
        val clientA = CordaRPCClient(nodeAddressA)
        val clientConnectionA = clientA.start(rpcUsernameA, rpcPasswordA)
        val proxyA = clientConnectionA.proxy
        logger.info("Connection to Node A established OK")

//        val nodeAddressB = parse(args[3])
//        val rpcUsernameB = args[4]
//        val rpcPasswordB = args[5]
//        val clientB = CordaRPCClient(nodeAddressB)
//        val clientConnectionB = clientB.start(rpcUsernameB, rpcPasswordB)
//        val proxyB = clientConnectionB.proxy
//        logger.info("Connection to Node B established OK")

        val mapper = ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        val writer = mapper.writerWithDefaultPrettyPrinter()

        // Interact with the node.
        // Example #1, here we print the nodes on the network.
        val networkMapSnapshot = proxyA.networkMapSnapshot()
        println("\n-- Here is the networkMap snapshot --")
        logger.info("networkMapSnapshot=\n{}", writer.writeValueAsString(networkMapSnapshot))

        val nodeDiagnosticInfo = proxyA.nodeDiagnosticInfo()
        logger.info("nodeDiagnosticInfo=\n{}", writer.writeValueAsString(nodeDiagnosticInfo))

        // Example #2, here we print the PartyB's node info
        val me = proxyA.nodeInfo().legalIdentities.first().name
        println("\n-- Here is the node info of the node that the client connected to --")
        logger.info("{}", me)

        // Example #3 We invoke a flow dynamically from the HTTP request
        val nodeA = networkMapSnapshot.single { n -> n.legalIdentities.any { li -> li.name.organisation.contains("PartyA") } }
        val partyA = nodeA.legalIdentities.first()
        val algorithmA = JvmObject(
            jvmTypeKind = JvmTypeKind.PRIMITIVE,
            primitiveValue = partyA.owningKey.algorithm,
            jvmType = JvmType(
                fqClassName = java.lang.String::class.java.name
            )
        )

        val formatA = JvmObject(
            jvmTypeKind = JvmTypeKind.PRIMITIVE,
            primitiveValue = partyA.owningKey.format,
            jvmType = JvmType(
                fqClassName = java.lang.String::class.java.name
            )
        )

        val encodedA = JvmObject(
            jvmTypeKind = JvmTypeKind.PRIMITIVE,
            primitiveValue = partyA.owningKey.encoded,
            jvmType = JvmType(
                fqClassName = ByteArray::class.java.name
            )
        )

        val publicKeyA = JvmObject(
            jvmTypeKind = JvmTypeKind.REFERENCE,
            jvmCtorArgs = listOf(algorithmA, formatA, encodedA),
            jvmType = JvmType(fqClassName = PublicKeyImpl::class.java.name)
        )

        val cordaX500NameA = JvmObject(
            jvmTypeKind = JvmTypeKind.REFERENCE,
            jvmType = JvmType(fqClassName = CordaX500Name::class.java.name),
            jvmCtorArgs = listOf(
                JvmObject(
                    jvmTypeKind = JvmTypeKind.PRIMITIVE,
                    primitiveValue = partyA.name.organisation,
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name
                    )
                ),
                JvmObject(
                    jvmTypeKind = JvmTypeKind.PRIMITIVE,
                    primitiveValue = partyA.name.locality,
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name
                    )
                ),
                JvmObject(
                    jvmTypeKind = JvmTypeKind.PRIMITIVE,
                    primitiveValue = partyA.name.country,
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name
                    )
                )
            )
        )

        val partyAJvmObject = JvmObject(
            JvmTypeKind.REFERENCE,
            jvmCtorArgs = listOf(cordaX500NameA, publicKeyA),
            jvmType = JvmType(fqClassName = Party::class.java.name)
        )

        val nodeB = networkMapSnapshot.single { n -> n.legalIdentities.any { li -> li.name.organisation.contains("PartyB") } }
        val partyB = nodeB.legalIdentities.first()
        val algorithmB = JvmObject(
            jvmTypeKind = JvmTypeKind.PRIMITIVE,
            primitiveValue = partyB.owningKey.algorithm,
            jvmType = JvmType(
                fqClassName = java.lang.String::class.java.name
            )
        )

        val formatB = JvmObject(
            jvmTypeKind = JvmTypeKind.PRIMITIVE,
            primitiveValue = partyB.owningKey.format,
            jvmType = JvmType(
                fqClassName = java.lang.String::class.java.name
            )
        )

        val encodedB = JvmObject(
            jvmTypeKind = JvmTypeKind.PRIMITIVE,
            primitiveValue = partyB.owningKey.encoded,
            jvmType = JvmType(
                fqClassName = ByteArray::class.java.name
            )
        )

        val publicKeyB = JvmObject(
            jvmTypeKind = JvmTypeKind.REFERENCE,
            jvmCtorArgs = listOf(algorithmB, formatB, encodedB),
            jvmType = JvmType(fqClassName = PublicKeyImpl::class.java.name)
        )

        val cordaX500NameB = JvmObject(
            jvmTypeKind = JvmTypeKind.REFERENCE,
            jvmType = JvmType(fqClassName = CordaX500Name::class.java.name),
            jvmCtorArgs = listOf(
                JvmObject(
                    jvmTypeKind = JvmTypeKind.PRIMITIVE,
                    primitiveValue = partyB.name.organisation,
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name
                    )
                ),
                JvmObject(
                    jvmTypeKind = JvmTypeKind.PRIMITIVE,
                    primitiveValue = partyB.name.locality,
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name
                    )
                ),
                JvmObject(
                    jvmTypeKind = JvmTypeKind.PRIMITIVE,
                    primitiveValue = partyB.name.country,
                    jvmType = JvmType(
                        fqClassName = java.lang.String::class.java.name
                    )
                )
            )
        )

        val partyBJvmObject = JvmObject(
            JvmTypeKind.REFERENCE,
            jvmCtorArgs = listOf(cordaX500NameB, publicKeyB),
            jvmType = JvmType(fqClassName = Party::class.java.name)
        )

        try {


            val req = InvokeContractV1Request(
                flowInvocationType = FlowInvocationType.FLOW_DYNAMIC,
                flowFullClassName = ExampleFlow.Initiator::class.java.name,
                params = listOf(
                    JvmObject(
                        JvmTypeKind.PRIMITIVE,
                        primitiveValue = 42,
                        jvmType = JvmType(fqClassName = Integer::class.java.name)
                    ),
                    partyBJvmObject
                )
            )
            logger.info("Req::ExampleFlow.Initiator={}", writer.writeValueAsString(req))

            val flowOut = dynamicInvoke(proxyA, req)
            logger.info("flowOut1={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        try {
            val req = InvokeContractV1Request(
                flowInvocationType = FlowInvocationType.FLOW_DYNAMIC,
                flowFullClassName = DummyFlow::class.java.name,
                params = listOf(
                    JvmObject(
                        JvmTypeKind.PRIMITIVE,
                        JvmType(
                            fqClassName = Integer::class.java.name
                        ),
                        primitiveValue = 42
                    )
                ),
                timeoutMs = BigDecimal.valueOf(60000)
            )
            logger.info("Req2={}", writer.writeValueAsString(req))
            val flowOut = dynamicInvoke(proxyA, req)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        try {
            val req = InvokeContractV1Request(
                flowFullClassName = MultipleNestedGenericsFlow::class.java.name,
                flowInvocationType = FlowInvocationType.FLOW_DYNAMIC,
                params = listOf(
                    JvmObject(
                        JvmTypeKind.REFERENCE,
                        JvmType(
                            fqClassName = Tractor::class.java.name
                        ),
                        jvmCtorArgs = listOf(
                            JvmObject(
                                JvmTypeKind.PRIMITIVE,
                                JvmType(fqClassName = String::class.java.name),
                                primitiveValue = "MyCoolTractor12345"
                            ),
                            JvmObject(
                                jvmTypeKind = JvmTypeKind.REFERENCE,
                                jvmType = JvmType(
                                    fqClassName = ConstructableArrayList::class.java.name
                                ),
                                jvmCtorArgs = listOf(
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.REFERENCE,
                                        jvmType = JvmType(
                                            fqClassName = SolidWheel::class.java.name
                                        ),
                                        jvmCtorArgs = listOf(
                                            JvmObject(
                                                jvmTypeKind = JvmTypeKind.REFERENCE,
                                                jvmType = JvmType(fqClassName = TitaniumRim::class.java.name),
                                                jvmCtorArgs = listOf(
                                                    JvmObject(
                                                        jvmTypeKind = JvmTypeKind.PRIMITIVE,
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
                                jvmTypeKind = JvmTypeKind.REFERENCE,
                                jvmType = JvmType(fqClassName = CombustionEngine::class.java.name),
                                jvmCtorArgs = listOf(
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.PRIMITIVE,
                                        jvmType = JvmType(fqClassName = Int::class.java.name),
                                        primitiveValue = 500000 // very strong tractor engine
                                    )
                                )
                            )
                        )
                    )
                ),
                timeoutMs = BigDecimal.valueOf(60000)
            )
            logger.info("Req3={}", writer.writeValueAsString(req))
            val flowOut = dynamicInvoke(proxyA, req)
            logger.info("flowOut={}", flowOut)
        } catch (ex: Throwable) {
            throw ex
        }

        // Pull in a jar from another example project and try and invoke the flows of it
        try {
            val req = InvokeContractV1Request(
                flowFullClassName = "net.corda.samples.obligation.flows.IOUIssueFlow",
                flowInvocationType = FlowInvocationType.TRACKED_FLOW_DYNAMIC,
                params = listOf(
                    JvmObject(
                        jvmTypeKind = JvmTypeKind.REFERENCE,
                        jvmType = JvmType(fqClassName = "net.corda.samples.obligation.states.IOUState"),
                        jvmCtorArgs = listOf(
                            JvmObject(
                                jvmTypeKind = JvmTypeKind.REFERENCE,
                                jvmType = JvmType(fqClassName = "net.corda.core.contracts.Amount"),
                                jvmCtorArgs = listOf(
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.PRIMITIVE,
                                        jvmType = JvmType(fqClassName = Long::class.java.name),
                                        primitiveValue = 42
                                    ),
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.REFERENCE,
                                        jvmType = JvmType(fqClassName = Currency::class.java.name),
                                        jvmCtorArgs = listOf(
                                            JvmObject(
                                                JvmTypeKind.PRIMITIVE,
                                                JvmType(fqClassName = String::class.java.name),
                                                "USD"
                                            )
                                        )
                                    )
                                )
                            ),
                            partyAJvmObject,
                            partyBJvmObject,
                            JvmObject(
                                jvmTypeKind = JvmTypeKind.REFERENCE,
                                jvmType = JvmType(fqClassName = "net.corda.core.contracts.Amount"),
                                jvmCtorArgs = listOf(
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.PRIMITIVE,
                                        jvmType = JvmType(fqClassName = Long::class.java.name),
                                        primitiveValue = 1
                                    ),
                                    JvmObject(
                                        jvmTypeKind = JvmTypeKind.REFERENCE,
                                        jvmType = JvmType(fqClassName = Currency::class.java.name),
                                        jvmCtorArgs = listOf(
                                            JvmObject(
                                                JvmTypeKind.PRIMITIVE,
                                                JvmType(fqClassName = String::class.java.name),
                                                "USD"
                                            )
                                        )
                                    )
                                )
                            ),
                            JvmObject(
                                JvmTypeKind.REFERENCE,
                                JvmType("net.corda.core.contracts.UniqueIdentifier"),
                                null,
                                listOf(
                                    JvmObject(
                                        JvmTypeKind.PRIMITIVE,
                                        JvmType(String::class.java.name),
                                        UUID.randomUUID().toString()
                                    )
                                )
                            )
                        )
                    )
                ),
                timeoutMs = BigDecimal.valueOf(60000)
            )

            val ndi = proxyA.nodeDiagnosticInfo()
            logger.info("NodeDiagnosticInfo=${ndi}")

            val cwd = Paths.get("").toAbsolutePath().toString()
            logger.info("CWD=${cwd}")

            // Upload obligation-cordapp contracts jar file to node A
            try {
                val jarFilename = "contracts-1.0.jar"
                val jarRelativePath = "../../../Advanced/obligation-cordapp/contracts/build/libs/"
                val jarPath = Paths.get(cwd, jarRelativePath, jarFilename).toAbsolutePath().toString()
                logger.info("contracts jar path: $jarPath")
                jcl.add(jarPath)
                logger.info("Added contracts jar to JCL OK")
                val jarInputStream = File(jarPath).inputStream()
                val jarSecureHash = proxyA.uploadAttachmentWithMetadata(jarInputStream, filename = jarFilename, uploader = "Peter")
                logger.info("Uploaded contracts JAR secure hash: $jarSecureHash")
            } catch(ex: CordaRuntimeException) {
                // for some reason the cause field is not set up with an instance of DuplicateAttachmentException
                // so we resort to checking the class name via string comparison instead
                if (ex.originalExceptionClassName == DuplicateAttachmentException::class.java.name) {
                    logger.info("contracts Jar already uploaded before. Skipping...")
                } else {
                    throw ex
                }
            } catch (ex: Throwable) {
                throw ex
            }

            // Upload obligation-cordapp workflows jar file to node A
            try {
                val jarFilename = "workflows-1.0.jar"
                val jarRelativePath = "../../../Advanced/obligation-cordapp/workflows/build/libs/"
                val jarPath = Paths.get(cwd, jarRelativePath, jarFilename).toAbsolutePath().toString()
                logger.info("workflows jar path: $jarPath")
                jcl.add(jarPath)
                logger.info("Added contracts jar to JCL OK")
                val jarInputStream = File(jarPath).inputStream()
                val jarSecureHash = proxyA.uploadAttachmentWithMetadata(jarInputStream, filename = jarFilename, uploader = "Peter")
                logger.info("Uploaded workflows JAR secure hash: $jarSecureHash")
            } catch(ex: CordaRuntimeException) {
                // for some reason the cause field is not set up with an instance of DuplicateAttachmentException
                // so we resort to checking the class name via string comparison instead
                if (ex.originalExceptionClassName == DuplicateAttachmentException::class.java.name) {
                    logger.info("contracts Jar already uploaded before. Skipping...")
                } else {
                    throw ex
                }
            } catch (ex: Throwable) {
                throw ex
            }

            logger.info("Req::JSON::FlowOut-Advanced-Obligation-IOUIssue\n{}", writer.writeValueAsString(req))
            val flowOut = dynamicInvoke(proxyA, req)
            logger.info("FlowOut-Advanced-Obligation-IOUIssue=${flowOut}")
        } catch (ex: Throwable) {
            throw ex
        }

        //Close the client connection
        clientConnectionA.close()
    }
}