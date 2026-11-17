package com.unclema123.pignpc

import javafx.application.Application

/**
 * 启动入口。
 *
 * 注意：这个类**不能**继承 Application，否则以 classpath 方式打包后
 * 启动器会报「JavaFX runtime components are missing」。
 */
fun main(args: Array<String>) {
    Application.launch(MainApp::class.java, *args)
}