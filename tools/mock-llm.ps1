$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any, 8765)
$listener.Start()
Write-Output "mock llm listening on 8765"
while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $stream = $client.GetStream()
        $buf = New-Object byte[] 4096

        # 先读完整的请求头
        $head = ""
        while (-not $head.Contains("`r`n`r`n")) {
            $n = $stream.Read($buf, 0, $buf.Length)
            if ($n -le 0) { break }
            $head += [Text.Encoding]::ASCII.GetString($buf, 0, $n)
        }

        # 再按 Content-Length 读完请求体，避免客户端写入时被 RST
        $len = 0
        $m = [regex]::Match($head, 'Content-Length:\s*(\d+)', 'IgnoreCase')
        if ($m.Success) { $len = [int]$m.Groups[1].Value }
        $split = $head.IndexOf("`r`n`r`n")
        $have = if ($split -ge 0) { $head.Length - ($split + 4) } else { 0 }
        $remain = $len - $have
        while ($remain -gt 0) {
            $n = $stream.Read($buf, 0, [Math]::Min($buf.Length, $remain))
            if ($n -le 0) { break }
            $remain -= $n
        }

        # 回包：内容故意带她的口吻，便于在聊天记录里认出是大模型回的
        $json = '{"choices":[{"message":{"role":"assistant","content":"我说的都对，你不许反驳"}}]}'
        $body = [Text.Encoding]::UTF8.GetBytes($json)
        $header = "HTTP/1.1 200 OK`r`n" +
                  "Content-Type: application/json; charset=utf-8`r`n" +
                  "Content-Length: $($body.Length)`r`n" +
                  "Connection: close`r`n`r`n"
        $hb = [Text.Encoding]::ASCII.GetBytes($header)
        $stream.Write($hb, 0, $hb.Length)
        $stream.Write($body, 0, $body.Length)
        $stream.Flush()
        Write-Output "served: body=$($body.Length)B"
    } catch {
        Write-Output "err: $_"
    } finally {
        try { $client.Close() } catch { }
    }
}