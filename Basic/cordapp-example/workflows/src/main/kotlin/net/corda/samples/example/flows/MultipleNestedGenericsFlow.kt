package net.corda.samples.example.flows

import net.corda.core.flows.*
import net.corda.core.serialization.CordaSerializable

abstract class Vehicle<T, E>(val id: String, val wheels: List<T>, val engine: E)
abstract class Rim(val colorName: String)
abstract class Engine(val horsePower: Int)

@CordaSerializable()
data class Car(val _id: String, val _wheels: List<SolidWheel<AluminiumRim>>, val _engine: ElectricMotor)
    : Vehicle<SolidWheel<AluminiumRim>, ElectricMotor>(_id, _wheels, _engine)

@CordaSerializable()
data class Tractor(val _id: String, val _wheels: List<SolidWheel<TitaniumRim>>, val _engine: CombustionEngine)
    : Vehicle<SolidWheel<TitaniumRim>, CombustionEngine>(_id, _wheels, _engine)

@CordaSerializable()
data class SolidWheel<R>(val rim: R)

@CordaSerializable()
data class AluminiumRim(val _colorName: String): Rim(_colorName)

@CordaSerializable()
data class TitaniumRim(val _colorName: String): Rim(_colorName)

@CordaSerializable()
data class CombustionEngine(val _horsePower: Int): Engine(_horsePower)

@CordaSerializable()
data class ElectricMotor(val _horsePower: Int): Engine(_horsePower)

@StartableByRPC
class MultipleNestedGenericsFlow<T1, T2>(private val vehicle: Vehicle<T1, T2>): FlowLogic<String>() {
    override fun call(): String {

        return "This is your vehicle ID:${vehicle.id}"
    }
}
