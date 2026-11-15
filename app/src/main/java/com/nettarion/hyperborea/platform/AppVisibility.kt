package com.nettarion.hyperborea.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Registered before any activity starts, independently of when the bridge service is created. */
@Singleton
class AppVisibility @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private val started = Collections.newSetFromMap(IdentityHashMap<Activity, Boolean>())
    private val _foreground = MutableStateFlow(false)
    val foreground = _foreground.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        _foreground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        _foreground.value = started.isNotEmpty()
    }

    override fun onActivityDestroyed(activity: Activity) = onActivityStopped(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
