package net.corda.samples.example.flows

import net.corda.core.flows.*

@StartableByRPC
class DummyFlow(private val iouValue: Int): FlowLogic<String>() {
    override fun call(): String {
        return "Hello World! $iouValue"
    }
}
