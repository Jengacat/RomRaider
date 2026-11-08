Unofficial test build of RomRaider, not a RomRaider release. It's RomRaider 1.1.0 plus a new logger protocol,
**SUBUDS**: RAM logging over UDS on CAN for the 2015-2018 Subaru 2.0 DIT ECUs (WRX, Forester XT). It defines the
selected parameters' RAM addresses with service 0x2C and reads them all back with one 0x22 request per poll, in the
extended diagnostic session, with no security access. Nothing is written to the ECU.

**Not yet tested on a car.** It has been checked against the ECU's own code in emulation: for every supported ROM,
all 70 parameters read through SUBUDS return the same bytes as SSM.

### What to download

* `RomRaider-SUBUDS-@TAG@-windows.zip` (or `-linux.zip`)
* `FA20DIT_Logger.xml`: the logger definition with the SUBUDS section (46 stock parameters plus 24 that otherwise
  need the logging patch; with SUBUDS they work on a stock ROM).

### How to test

1. Unzip anywhere (it runs alongside an installed RomRaider). On Windows the OpenPort 2.0 needs **32-bit Java 8**,
   the same one an installed RomRaider uses; if `javaw.exe` isn't the 32-bit one, set `java_path` in `run.bat`.
2. Run `run.bat`, open the Logger, and load `FA20DIT_Logger.xml` as the logger definition.
3. Settings → Logger Protocol → **SUBUDS**, port = your OpenPort (J2534).
4. Ignition on (engine running is fine), select a few parameters, and start logging.
5. Please open an issue with your ROM's calibration ID and the result, good or bad. If it fails,
   `%HOMEPATH%\.RomRaider\romraider_sout.log` says why.

### Source

Built from commit @SHA@ on branch `subuds-test-build` (`Subaru-UDS-Logging` plus this build workflow).
RomRaider is GPL-2.0; the license and source are in each zip.
