@echo off
setlocal
if defined JAVA_HOME (
  set "OFDH_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  set "OFDH_JAVA=java.exe"
)
if not defined OFDH_MAX_HEAP set "OFDH_MAX_HEAP=1024m"
"%OFDH_JAVA%" "-Xmx%OFDH_MAX_HEAP%" -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp "%~dp0..\lib\*" org.openofdh.cli.OfdhCommand %*
exit /b %ERRORLEVEL%
