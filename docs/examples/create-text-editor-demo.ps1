param([Parameter(Mandatory=$true)][string]$OutputDirectory)

# Windows-only synthetic lesson. This is not an ASR result or a campus recording.
$ErrorActionPreference='Stop'
$output=[IO.Path]::GetFullPath($OutputDirectory)
if(Test-Path -LiteralPath $output){throw 'Use a new output directory; existing files will not be overwritten.'}
Get-Command ffmpeg,ffprobe -ErrorAction Stop|Out-Null
$font=Join-Path $env:WINDIR 'Fonts/msyh.ttc'
if(!(Test-Path -LiteralPath $font)){throw 'Microsoft YaHei font is required for this Windows example.'}
New-Item -ItemType Directory -Path $output|Out-Null
$clips=@(
    @{title="01   通过文字回看课程`n`n单个视频 · 句段时间轴 · 对应画面";text='这是一段合成课程演示。今天我们用二分查找，演示如何通过文字回看视频。'},
    @{title="02   二分查找的前提`n`n数组有序`n比较中间元素，排除一半区间";text='二分查找要求数组有序。每次比较中间元素，再排除不可能包含目标的那一半。'},
    @{title="03   这段需要删除`n`n故意插入的重录片段`n取消勾选这句，预览与导出都会跳过";text='这一句是特意插入的重录片段。选中这段文字，把它从成片里面删掉。'},
    @{title="04   保留完整讲解`n`n保留第 1、2、4 段`n文字定位 → 选段预览 → 保存 → 导出";text='保留第一段、第二段和最后一段。预览会跳过删除的片段，导出会真正生成新的视频。'}
)
$voice=New-Object -ComObject SAPI.SpVoice
try {
    $voices=$voice.GetVoices('Language=804')
    if($voices.Count-eq0){throw 'A built-in Simplified Chinese Windows speech voice is required.'}
    $voice.Voice=$voices.Item(0)
    $voice.Rate=0
    $voice.Volume=100
    foreach($i in 0..($clips.Count-1)) {
        $wav=Join-Path $output ('speech-{0:00}.wav'-f($i+1))
        $stream=New-Object -ComObject SAPI.SpFileStream
        try {
            $stream.Open($wav,3,$false)
            $voice.AudioOutputStream=$stream
            [void]$voice.Speak($clips[$i].text,0)
            $stream.Close()
        } finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($stream) }
        $seconds=& ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 $wav
        if($LASTEXITCODE-ne0){throw 'Cannot probe generated speech.'}
        $duration=[double]::Parse(($seconds|Out-String).Trim(),[Globalization.CultureInfo]::InvariantCulture)
        $clips[$i].durationMs=[int64]([Math]::Ceiling($duration*25)*40)
        [IO.File]::WriteAllText((Join-Path $output ('slide-{0:00}.txt'-f($i+1))),$clips[$i].title,[Text.UTF8Encoding]::new($false))
    }
} finally { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($voice) }

function Seconds($ms){ ([double]$ms/1000).ToString('0.000',[Globalization.CultureInfo]::InvariantCulture) }
function Stamp($ms){$t=[TimeSpan]::FromMilliseconds($ms);'{0:00}:{1:00}:{2:00},{3:000}'-f[int]$t.TotalHours,$t.Minutes,$t.Seconds,$t.Milliseconds}
$total=($clips|ForEach-Object {$_.durationMs}|Measure-Object -Sum).Sum
$arguments=@('-hide_banner','-loglevel','error','-f','lavfi','-i',('color=c=0x131b2a:s=1280x720:r=25:d='+(Seconds $total)))
foreach($i in 0..($clips.Count-1)){$arguments+=@('-i',('speech-{0:00}.wav'-f($i+1)))}
$escapedFont=$font.Replace('\','/').Replace(':','\:')
$filters=[Collections.Generic.List[string]]::new()
$srt=[Collections.Generic.List[string]]::new()
$offset=0L
foreach($i in 0..($clips.Count-1)) {
    $start=$offset; $offset+=$clips[$i].durationMs
    $inputLabel=if($i-eq0){'[0:v]'}else{'[v'+($i-1)+']'}
    $textFile='slide-{0:00}.txt'-f($i+1)
    $filters.Add($inputLabel+"drawtext=fontfile='$escapedFont':textfile='$textFile':fontsize=44:fontcolor=white:x=72:y=210:line_spacing=22:enable='gte(t,"+(Seconds $start)+')*lt(t,'+(Seconds $offset)+")'[v$i]")
    $filters.Add('['+($i+1)+':a]aresample=48000,apad,atrim=duration='+(Seconds $clips[$i].durationMs)+",asetpts=PTS-STARTPTS[a$i]")
    $srt.Add([string]($i+1));$srt.Add((Stamp $start)+' --> '+(Stamp $offset));$srt.Add($clips[$i].text);$srt.Add('')
}
[IO.File]::WriteAllText((Join-Path $output 'demo-subtitles.srt'),($srt-join"`n"),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $output 'footnote.txt'),'合成课程演示  /  系统语音 + 手工字幕  /  非校园录课与 ASR 结果',[Text.UTF8Encoding]::new($false))
$filters.Add('[v'+($clips.Count-1)+"]drawtext=fontfile='$escapedFont':textfile='footnote.txt':fontsize=22:fontcolor=0x94a3b8:x=72:y=648[v]")
$audioLabels=(0..($clips.Count-1)|ForEach-Object {"[a$_]"})-join''
$filters.Add($audioLabels+'concat=n='+$clips.Count+':v=0:a=1[a]')
[IO.File]::WriteAllText((Join-Path $output 'filters.txt'),($filters-join';'),[Text.UTF8Encoding]::new($false))
$arguments+=@('-/filter_complex','filters.txt','-map','[v]','-map','[a]','-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac','-movflags','+faststart','-n','synthetic-lesson-demo.mp4')
Push-Location -LiteralPath $output
try { & ffmpeg @arguments; if($LASTEXITCODE-ne0){throw 'Synthetic demo video creation failed.'} }
finally {Pop-Location}
@{material='Synthetic lesson, Windows speech and manually assembled subtitles; not ASR';durationMs=$total;clips=$clips}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath (Join-Path $output 'material.json') -Encoding utf8
Write-Output "Created synthetic lesson with $($clips.Count) measured audio segments, $total ms."
