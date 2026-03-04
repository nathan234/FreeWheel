package org.freewheel.services

import android.content.Intent
import org.freewheel.WearActivity
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.freewheel.shared.Constants


class StartActivityService: WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path == Constants.wearOsStartPath) {
            val intent = Intent(this, WearActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }
}