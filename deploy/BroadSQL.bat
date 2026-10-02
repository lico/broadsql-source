@ECHO OFF
REM | ==================================================== |
REM | BroadSQL release 5.4.0, 2008-2026 by UpAndCoding.com |
REM |                                                      |
REM | Documentation and latest release available on:       |
REM | https://www.broadsql.com                             |
REM | ==================================================== |
REM

REM Run from the installation folder, whatever the current directory: every relative path (conf/, lib/,
REM the connections file, samples/WorldDB of the WORLD connection) is relative to it
pushd "%~dp0"

IF NOT "%~1" == "" GOTO :ParamExists
:NoParam
java -Xms2048m -Xmx4096m  -Dbsql.settings=conf/BroadSQL.ini -Dfile.encoding=Cp850 -Dlogback.configurationFile=conf/logback-broadsql.xml -cp lib/*;drivers/*;extensions/* com.upandcoding.broadsql.controller.BroadSQL -to=$CDF
Goto :END
:ParamExists
java -Xms2048m -Xmx4096m  -Dbsql.settings=conf/BroadSQL.ini -Dfile.encoding=Cp850 -Dlogback.configurationFile=conf/logback-broadsql.xml -cp lib/*;drivers/*;extensions/* com.upandcoding.broadsql.controller.BroadSQL -to=%1
:END
popd
