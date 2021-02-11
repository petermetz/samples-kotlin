package org.hyperledger.cactus.plugin.ledger.connector.corda.server.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonValue
import javax.validation.constraints.DecimalMax
import javax.validation.constraints.DecimalMin
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size
import javax.validation.Valid

/**
* 
* Values: pRIMITIVE,rEFERENCE
*/
enum class JvmTypeKind(val value: kotlin.String) {

    pRIMITIVE("org.hyperledger.cactus.JvmTypeKind.PRIMITIVE"),

    rEFERENCE("org.hyperledger.cactus.JvmTypeKind.REFERENCE");

}

