java -jar javacpp-1.5.6.jar -Dorg.bytedeco.javacpp.logger.debug=true -nodelete org/scrcpy/ScrcpyLibraryInfo.java 2>&1 |tee PASS1.txt
java -jar javacpp-1.5.6.jar -Dorg.bytedeco.javacpp.logger.debug=true -nodelete org/scrcpy/ScrcpyLibrary.java 2>&1 |tee PASS2.txt
