package dev.ynagai.autograph

import android.content.Context
import androidx.startup.Initializer

/** Captures the application [Context] at app startup so the default [SeqStore] can use it. */
public class AutographInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        AutographAndroidContext.applicationContext = context.applicationContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
