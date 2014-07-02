package chngName

import groovy.util.logging.Slf4j

class ChngName {
    private  static final PREFIX = "chngName"

    @SuppressWarnings(["SystemExit", "CatchThrowable"])
    static void main(String... args) {
        if (args.length != 1) {
            showUsage()
            System.exit(1)
        }
        try {
            run()
        } catch (Throwable throwable) {
            // Java returns exit code 0 if it terminates because of an uncaught Throwable.
            // That's bad if we have a process like Bamboo depending on errors being non-zero.
            // Thus, we catch all Throwables and explicitly set the exit code.
            println( "Unexpected error: ${throwable}" )
            System.exit(1)
        }
        System.exit(0);
    }

    private static showUsage() {
        System.err.println( "USAGE: java -jar chngName.jar CONFIG_FILE" )
    }

    private static void run() {
        println( 'Hello World.' );
    }

}
