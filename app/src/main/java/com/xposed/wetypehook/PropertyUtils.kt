package com.xposed.wetypehook

import android.annotation.SuppressLint
import com.xposed.wetypehook.xposed.Log
import java.lang.reflect.Method

object PropertyUtils {
    private val get: Method by lazy {
        @SuppressLint("PrivateApi")
        val cls = Class.forName("android.os.SystemProperties")
        cls.getDeclaredMethod("get", String::class.java, String::class.java)
    }

    operator fun get(prop: String, defaultValue: String?): String? {
        kotlin.runCatching {
            get.invoke(null, prop, defaultValue) as String?
        }.onFailure {
            Log.e(it)
        }.onSuccess {
            return it
        }
        return defaultValue
    }
}
