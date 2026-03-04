package org.freewheel

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import org.freewheel.compose.ComposeActivity
import kotlinx.coroutines.*


class SplashActivity: Activity() {

    @SuppressLint("InflateParams")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, ComposeActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeSceneTransitionAnimation(this).toBundle()

        val job = Job()
        val scope = CoroutineScope(job)
        scope.launch {
            startActivity(intent, options)
            delay(5000)
            finish()
        }
    }
}
