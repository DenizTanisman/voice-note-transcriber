package dev.deniz.ses

import android.app.Application
import dev.deniz.ses.asr.Engine
import dev.deniz.ses.data.Db
import dev.deniz.ses.data.Prefs
import dev.deniz.ses.service.Jobs
import dev.deniz.ses.service.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        db = Db.get(this)
        prefs = Prefs(this)
        db.resetStale()
        Notifier.channels(this)
        jobs = Jobs(this, db, prefs, Engine(this, prefs))
    }

    companion object {
        lateinit var app: App; private set
        lateinit var db: Db; private set
        lateinit var prefs: Prefs; private set
        lateinit var jobs: Jobs; private set
    }
}
