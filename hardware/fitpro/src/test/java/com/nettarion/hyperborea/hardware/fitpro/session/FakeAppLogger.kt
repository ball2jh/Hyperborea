package com.nettarion.hyperborea.hardware.fitpro.session

import com.nettarion.hyperborea.core.AppLogger

class FakeAppLogger : AppLogger {
    override fun d(tag: String, message: String) {}
    override fun i(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
