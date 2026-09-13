package io.reactivex.rxjava3.core

import io.reactivex.rxjava3.functions.Consumer
import io.reactivex.rxjava3.disposables.Disposable

class Single<T> private constructor(
    private val result: Result<T>,
    private val successObservers: List<Consumer<in T>>,
) {
    var lastDisposable: Disposable? = null
        private set

    fun doOnSuccess(observer: Consumer<in T>): Single<T> =
        Single(result, successObservers + observer)

    fun blockingGet(): T {
        val value = result.getOrThrow()
        successObservers.forEach { observer -> observer.accept(value) }
        return value
    }

    fun subscribe(
        onSuccess: Consumer<in T>,
        onError: Consumer<in Throwable>,
    ): Disposable {
        val disposable = TestDisposable()
        lastDisposable = disposable
        result.fold(
            onSuccess = { value ->
                if (!disposable.isDisposed) {
                    successObservers.forEach { observer -> observer.accept(value) }
                    onSuccess.accept(value)
                }
            },
            onFailure = { error ->
                if (!disposable.isDisposed) onError.accept(error)
            },
        )
        return disposable
    }

    companion object {
        fun <T> just(value: T): Single<T> = Single(Result.success(value), emptyList())

        fun <T> error(error: Throwable): Single<T> = Single(Result.failure(error), emptyList())
    }
}

private class TestDisposable : Disposable {
    override var isDisposed: Boolean = false
        private set

    override fun dispose() {
        isDisposed = true
    }
}
