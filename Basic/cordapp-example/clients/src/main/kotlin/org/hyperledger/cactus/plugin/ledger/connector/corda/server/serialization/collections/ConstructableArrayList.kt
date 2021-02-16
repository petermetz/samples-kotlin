package org.hyperledger.cactus.plugin.ledger.connector.corda.server.serialization.collections

class ConstructableArrayList<T>(items: Array<Any>): ArrayList<T>() {
    init {
        items.forEach { i -> add(i as T) }
    }
}
