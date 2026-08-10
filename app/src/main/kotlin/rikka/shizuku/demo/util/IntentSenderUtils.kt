package rikka.shizuku.demo.util

import android.content.IIntentSender
import android.content.IntentSender

object IntentSenderUtils {

    fun newInstance(binder: IIntentSender): IntentSender {
        return IntentSender::class.java.getConstructor(IIntentSender::class.java).newInstance(binder)
    }
}
