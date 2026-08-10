package rikka.shizuku.demo.util

abstract class Singleton<T> {

    private var mInstance: T? = null

    protected abstract fun create(): T

    @Synchronized
    fun get(): T {
        if (mInstance == null) {
            mInstance = create()
        }
        return mInstance!!
    }
}
