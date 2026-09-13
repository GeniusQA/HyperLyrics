package io.reactivex.rxjava3.functions

fun interface Consumer<T> {
    fun accept(value: T)
}
