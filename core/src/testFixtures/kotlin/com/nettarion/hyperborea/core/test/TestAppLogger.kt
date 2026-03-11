package com.nettarion.hyperborea.core.test

import com.nettarion.hyperborea.core.AppLogger

class TestAppLogger : AppLogger {
    override fun d(tag: String, message: String) {}
    override fun i(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
