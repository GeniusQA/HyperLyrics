package io.reactivex.rxjava3.disposables

interface Disposable {
    val isDisposed: Boolean

    fun dispose()
}
