package app.listentogether.ui

/** 把服务器时间（毫秒）按本机时区显示成「13:05」；不是今天的显示成「11月7日 13:05」。 */
expect fun wallClockText(epochMs: Long): String
