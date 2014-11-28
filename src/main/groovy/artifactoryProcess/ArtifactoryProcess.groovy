package artifactoryProcess

import com.xlson.groovycsv.CsvIterator
import groovy.util.logging.Log
import org.jfrog.artifactory.client.Artifactory
import org.jfrog.artifactory.client.Repositories
import org.jfrog.artifactory.client.model.Item
import org.jfrog.artifactory.client.model.impl.RepositoryTypeImpl
import org.jfrog.artifactory.client.DownloadableArtifact;
import org.jfrog.artifactory.client.ItemHandle;
// import org.jfrog.artifactory.client.model.File; Conflicts with Java File class which we use elsewhere
import org.jfrog.artifactory.client.PropertiesHandler;
import org.jfrog.artifactory.client.model.Folder
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.ExampleMode;
import org.kohsuke.args4j.Option;
import groovy.transform.stc.ClosureParams;
import groovy.transform.stc.SimpleType;
import org.jfrog.artifactory.client.ArtifactoryClient;
import org.jfrog.artifactory.client.RepositoryHandle;
import com.xlson.groovycsv.CsvParser;

/* Blah!  Comment out the Grapes sections if you want to build a stand alone jar file.  Double Blah!! */
@Grapes([
        @GrabResolver(name='artifactory01.nexus.commercehub.com', root='http://artifactory01.nexus.commercehub.com/artifactory/ext-release-local/', m2Compatible=true),
        @Grab(group='net.sf.json-lib', module='json-lib', version='2.4', classifier='jdk15' ),
        @Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7'),
        @Grab( group='com.xlson.groovycsv', module='groovycsv', version='1.0' ),
        @GrabExclude(group='org.codehaus.groovy', module='groovy-xml')

])

@Grapes( [
        @Grab(group='org.kohsuke.args4j', module='args4j-maven-plugin', version='2.0.22'),
        @GrabExclude(group='org.codehaus.groovy', module='groovy-xml')
])

@Grapes([
        @Grab(group='org.jfrog.artifactory.client', module='artifactory-java-client-services', version='0.13'),
        @GrabExclude(group='org.codehaus.groovy', module='groovy-xml')
])
/* End of commented out code. */

/**
 * This groovy class is meant to mark artifacts for release and clean up old versions.
 *
 * The first mode is to mark artifacts with properties such as tested, releasable and production.
 *
 * The second mode could be to mark artifacts for removal based on FIFO counts of artifacts in the
 * states defined in the maxInState.csv file provided.  If you say want at most 5 versions of an
 * artifact in the production state and there are more than that, then the oldest versions could be
 * marked for removal.
 *
 * The third mode could be the actual deletion of any artifacts which were marked for removal.
 * The delay is to allow human intervention before wholesale deletion.
 *
 * The versionsToUse is an array of strings that are the start of builds that should be processed.
 *
 * There are two additional options.  The first is the dryRun option. This way you can get
 * an overview of what will be processed. If specified, no artifacts will be altered.
 * The second is full-log option which will display processing information which
 * can be useful if your script is not working quite right.
 *
 * Usage example
 *   groovy ArtifactoryProcess.groovy --dry-run --function mark --value production --must-have releasable --web-server http://yourWebServer/artifactory/ --domain <com.YourOrg> --repository libs-release-prod 1.0.1 1.0.2
 *  
 * @author Brian Carr (snippets from Jettro Coenradie, David Carr and others)
 */

class ArtifactoryProcess {
    public static final Set< String > validFunctions = [ 'mark', 'delete', 'clear', 'download', 'config', 'repoPrint', 'size' ];
    public static final Set< String > validParameters = [ 'function', 'value', 'mustHave', 'targetDir', 'maxInState', 'webServer', 'repoName', 'domain', 'userName', 'password', 'maxDays', 'minDays' ];

    @Option(name='--dry-run', usage='Don\'t change anything; just list what would be done')
    boolean dryRun;

    @Option(name='--full-log', usage='Display miscellaneous steps for processing artifacts')
    boolean fullLog;

//  eg  --function mark
    @Option(name='--function', metaVar='function', usage="function to perform on artifacts")
    String function;

//  eg  --value production
    @Option(name='--value', metaVar='value', usage="value to use with certain functions, often required")
    String value;

//  eg  --must-have releasable
    @Option(name='--must-have', metaVar='mustHave', usage="property required before applying delete, mark, download, size or clear, optional")
    String mustHave;

//  eg  --targetDir d:/temp/bin
    @Option(name='--targetDir', metaVar='targetDir', usage="target directory for downloaded artifacts")
    String targetDir;

//  eg  --maxInState MaxInState.csv
    @Option(name='--maxInState', metaVar='maxInState', usage="name of csv file with states and max counts, optional")
    String maxInState;

//  eg  --maxDay 14
    @Option(name='--maxDays', metaVar='maxDays', usage="max days to mark / delete artifact (ignores counts), optional")
    Float maxDays = 0;

//  eg  --minDay 3
    @Option(name='--minDays', metaVar='minDays', usage="min days before mark / delete artifacts (ignores counts), optional")
    Float minDays = 0;

//  eg  --offsetDays 1
    @Option(name='--offsetDays', metaVar='offsetDays', usage="offset for min and max days to adjust for marking delays")
    Float offsetDays = 0;

//  eg  --web-server 'http://artifactory01/'
    @Option(name='--web-server', metaVar='webServer', usage='URL to use to access Artifactory server')
    String webServer;

//  eg  --repository 'libs-release-prod'
    @Option(name='--repository', metaVar='repoName', usage='Name of the repository to scan.')
    String repoName;

//  eg  --domain 'org/apache'
    @Option(name='--domain', metaVar='domain', usage='Name of the domain to scan.')
    String domain;

//  eg  --userName cleaner
    @Option(name='--userName', metaVar='userName', usage='userName to use to access Artifactory server')
    String userName;

//  eg  --password SomePswd
    @Option(name='--password', metaVar='password', usage='Password to use to access Artifactory server.')
    String password;

    @Argument
    ArrayList<String> versionsToUse = new ArrayList<String>();

    class Branch{
        String branchName;
        List< PathAndDate > pathAndDate;
    }

    class PathAndDate{
        String path;
        Date dtCreated;
    }
    class StateRecord {
        String state;
        int cnt;
        float minDays;
        float maxDays;
        String divider;
        List< Branch > branches;
    }

    @SuppressWarnings(["SystemExit", "CatchThrowable"])
    static void main( String[] args ) {
        try {
        new ArtifactoryProcess().doMain( args );
        } catch (Throwable throwable) {
            // Java returns exit code 0 if it terminates because of an uncaught Throwable.
            // That's bad if we have a process like Bamboo depending on errors being non-zero.
            // Thus, we catch all Throwables and explicitly set the exit code.
            println( "Unexpected error: ${throwable}" )
            System.exit(1)
        }
        System.exit(0);
    }

    List< StateRecord > stateSet = [];

    Artifactory srvr;
    RepositoryHandle repo;
    private int numProcessed = 0;
    private long thisSize = 0;
    private long overAllSize = 0;
    String firstFunction;
    String lastConfig;

    void doMain( String[] args ) {
        CmdLineParser parser = new CmdLineParser( this );
        try {
            parser.parseArgument(args);
            if( function == 'config' && value == null ) {
                throw new CmdLineException("You must provide a config.csv file as the value if you specify the config function.");
            }
            firstFunction = function;   // Flag in case we recurse into config files, where did we start
            if( function == 'config' ) {
                processConfig();
                return;
            } else {
                checkParms();
            }

        } catch(CmdLineException ex) {
            System.err.println(ex.getMessage());
            System.err.println();
            System.err.println("groovy ArtifactoryProcess.groovy [--dry-run] [--full-log] --function <func> --value <val> --web-server http://YourWebServer/ --repository libs-release-local --domain <com/YourOrg> Version1 ...");
            parser.printUsage(System.err);
            System.err.println();
            System.err.println("  Example: groovy ArtifactoryProcess.groovy"+parser.printExample(ExampleMode.ALL)+" 1.0.1 1.0.2");
            System.err.println();
            System.err.println("  Supported functions include ${validFunctions}" );
            System.err.println();
            System.err.println("  Columns in config csv files can be ${validParameters}" );
            return;
        }

        String stateLims;
        if( maxInState != null && maxInState.size() > 0 ) stateLims = "(using stateLims)" else stateLims = "(no stateLims)"
        println( "Started processing of $function with ${(value==null)?mustHave:value} $stateLims on $webServer in $repoName/$domain with $versionsToUse." );
        
        withClient { newClient ->
            srvr = newClient;
            if( function == 'repoPrint' ) printRepositories();
            else {
                processRepo();
            }
            if( function == 'size' ) {
                int sizeM = overAllSize / 1000000;
                println "Size on ${ domain } was ${ sizeM } megabtyes."; }

        }
    }

    def processRepo() {
        numProcessed = 0;                      // Reset count from last repo.
        repo = srvr.repository( repoName );
        processArtifactsRecursive( domain );
        if( dryRun ) {
            println "$numProcessed folders would have been $function[ed] with $value.";
        } else {
            println "$numProcessed folders were $function[ed] with $value.";
        }

    }


    def processConfig() {
        File configCSV = new File( value );
        lastConfig = value;  // Record which csv file we have last dived into.
        Artifactory mySrvr = srvr; // Each line of config could have a different web server, preserve connection in case recursing
        configCSV.withReader {
            CsvIterator csvIt = CsvParser.parseCsv( it );
            for( csvRec in csvIt ) {
                if (fullLog) println("Step is ${csvRec}");
                Map cols = csvRec.properties.columns;
//                String func = csvRec.function;
//                def hasFunc = cols.containsKey( 'function' );
//                def has = cols.containsKey( 'targetDir' );
                if( cols.containsKey( 'function'   ) && !noValue( csvRec.function   ) ) function   = csvRec.function  ;
                if( cols.containsKey( 'value'      ) && !noValue( csvRec.value      ) ) value      = csvRec.value     ;
                if( cols.containsKey( 'targetDir'  ) && !noValue( csvRec.targetDir  ) ) targetDir  = csvRec.targetDir ;
                if( cols.containsKey( 'maxInState' ) && !noValue( csvRec.maxInState ) ) maxInState = csvRec.maxInState;
                if( cols.containsKey( 'webServer'  ) && !noValue( csvRec.webServer  ) ) webServer  = csvRec.webServer ;
                if( cols.containsKey( 'repoName'   ) && !noValue( csvRec.repoName   ) ) repoName   = csvRec.repoName  ;
                if( cols.containsKey( 'domain'     ) && !noValue( csvRec.domain     ) ) domain     = csvRec.domain    ;
                if( cols.containsKey( 'userName'   ) && !noValue( csvRec.userName   ) ) userName   = csvRec.userName  ;
                if( cols.containsKey( 'password'   ) && !noValue( csvRec.password   ) ) password   = csvRec.password  ;
                if( cols.containsKey( 'mustHave'   ) ) mustHave   = csvRec.mustHave; // Can clear out mustHave value
                if( cols.containsKey( 'minDays'   ) )  minDays    = csvRec.minDays;    // Can clear out mustHave value
                if( cols.containsKey( 'maxDays'   ) )  maxDays    = csvRec.maxDays;    // Can clear out mustHave value

//                if( minDays == null ) minDays = 0;
//                if( maxDays == null ) maxDays = 0;

                checkParms();
                withClient { newClient ->
                    srvr = newClient;
                    processRepo();
                }
                srvr = mySrvr;  // Restore previous web server connection
            }
        }
    }

    def checkParms() {
        statesFile();  // Process any maxInState file.
        String prefix;

        if( firstFunction == 'config' && function != 'config' ) {
            prefix = "While processing ${lastConfig} encountered, ";
        } else prefix = '';
        if( !validFunctions.contains( function ) ) {
            throw new CmdLineException( "${prefix}Unrecognized function ${function}, function is required and must be one of ${validFunctions}." );
        }
        if( function == 'mark' && noValue( value ) ) {
            throw new CmdLineException( "${prefix}You must provide a value to mark with if you specify the mark function." );
        }
        if( function == 'clear' && noValue( value ) ) {
            throw new CmdLineException( "${prefix}You must provide a value to clear with if you specify the clear function." );
        }
        if( function != 'repoPrint' && noValue( domain ) ) {
            throw new CmdLineException( "${prefix}You must provide a domain to use with the ${function} function." );
        }
        if( function == 'download' ) {
            if( noValue( targetDir ) ) targetDir = '.';
        }
        if( noValue( webServer ) || noValue( userName ) || noValue( password ) || noValue( repoName ) ) {
            throw new CmdLineException( "${prefix}You must provide the webServer, userName, password and repository name values to use." );
        }
        if( versionsToUse.size() == 0 && stateSet.size() == 0 && function != 'repoPrint' && function != 'size' ) {
            throw new CmdLineException( "${prefix}You must provide maxInState or a list of artifacts / versions to act upon." );
        }
        if( offsetDays == null || offsetDays < 0 ) {
            throw new CmdLineException( "${prefix}offsetDays of ${offsetDays}, must be number greater than zero." );
        }
        if( maxDays == null || maxDays < 0 ) {
            throw new CmdLineException( "${prefix}maxDays of ${maxDays}, must be number greater than zero." );
        }
        if( minDays == null || minDays < 0 ) {
            throw new CmdLineException( "${prefix}minDays of ${minDays}, must be number greater than zero." );
        }

    }
    void statesFile() {  // Process any maxInState file.
        if( !noValue( maxInState ) ) {
            stateSet.clear();                         // Throw away any previous states from last step
            if( fullLog ) println( "Parsing ${maxInState}." )
            File stateFile = new File( maxInState );
            def RC = stateFile.withReader {
                CsvIterator csvFile = CsvParser.parseCsv( it );
                for( csvRec in csvFile ) {

                    /* Set up default values for this new record. */

                    String state = "";
                    int count = 0;
                    float myMinDays = minDays;
                    float myMaxDays = maxDays;
                    String divider = "";

                    /* Pull out any values provided (templated code). */

                    Map cols = csvRec.properties.columns;
//                  def hasState = cols.containsKey( 'state' );  // Useful if exploring csv objects
                    if( cols.containsKey( 'State'   ) && !noValue( csvRec.State   ) ) state      = csvRec.State     ;
                    if( cols.containsKey( 'Divider' ) && !noValue( csvRec.Divider ) ) divider    = csvRec.Divider   ;
                    if( cols.containsKey( 'Count'   ) && !noValue( csvRec.Count   ) && csvRec.Count.integer ) count      = csvRec.Count.toInteger();   ;
                    if( cols.containsKey( 'MinDays' ) && !noValue( csvRec.MinDays ) && csvRec.MinDays.float ) myMinDays    = csvRec.MinDays.toFloat() - offsetDays;   ;
                    if( cols.containsKey( 'MaxDays' ) && !noValue( csvRec.MaxDays ) && csvRec.MaxDays.float ) myMaxDays    = csvRec.MaxDays.toFloat() - offsetDays;   ;
                    if( count < 0 ) count = 0;
                    if( myMinDays < 0 ) myMinDays = 0;
                    if( myMaxDays < 0 ) myMaxDays = 0;

                    List branches = [];

                    if( fullLog ) println( "State ${state} allowed ${count} within ${myMinDays} and ${myMaxDays} days, branches by ${divider}" );
                    // Iterator lies and claims there is a next when there isn't.  Force break on empty state.
                    stateSet.add(
                            new StateRecord( state: state, divider: divider, cnt: count, minDays: myMinDays, maxDays: myMaxDays, branches: branches ) );
                }
            }
        }

    }

    Boolean noValue( var ) {
        return var == null || var == '';
    }
    /**
     * Print information about all the available repositories in the configured Artifactory
     */
    def printRepositories() {
        Repositories repos = srvr.repositories();

        List repoList = repos.list( RepositoryTypeImpl.LOCAL  );
        for( it in repoList ) {
            println "key :" + it.key
            println "type : " + it.type
            println "description : " + it.description
            println "url : " + it.url
            println ""
        };

    }

    /**
     * Recursively removes all folders containing builds that start with the configured paths.
     *
     * @param path String containing the folder to check and use the childs to recursively check as well.
     * @return Number with the amount of folders that were processed.
     */
    private int processArtifactsRecursive( String path ) {
        ItemHandle item = repo.folder( path );
        Folder fldr;
        Boolean foundEndNode = false;
        try{
            fldr = item.info()
        } catch( Exception e ) {
            println( "Error accessing $webServer/$repoName/$path" );
            throw( e );
        };
        
        for( kid in fldr.children ) {
            boolean processed = false;
            if( function == 'size' ) {
//                boolean a = kid.folder;
                if( kid.folder ) {
                    processArtifactsRecursive( path + kid.uri );
                } else {
                    processSize( path + kid.uri );
                }
                if( domain == path ) {
                    int sizeM = thisSize / 1000000;
                    println "Size on ${path + kid.uri} was ${sizeM} megabytes.";
                    overAllSize += thisSize;
                    thisSize = 0;
                }
                processed = true;
            }
            if( stateSet.size() > 0 ) {
                if( isEndNode( kid.uri ) ) {
                    foundEndNode = true;
                    processed = putInGroup(path + kid.uri);
                }
            } else {
                versionsToUse.find { version ->
                    if (kid.uri.startsWith('/' + version)) {
                        numProcessed += processItem(path + kid.uri);
                        processed = true;
                        return true; // Once we find a match, no others are interesting, we are outta here
                    } else return false; // Just formalize the on to next iterator
                }
            }
            if( !processed && kid.folder ) {
                processArtifactsRecursive(path + kid.uri);
            }
        }

        /* If we are counting number in each state, our lists should be all set now */

        if( foundEndNode && stateSet.size() > 0 ) {
            processSet();
        }

        return numProcessed;
    }

    private boolean processedThis( String vrsn, kid ) {
        if( kid.uri.startsWith('/' + vrsn )) {
            numProcessed += processItem( vrsn + kid.uri );
            return true; // Once we find a match, no others are interesting, we are outta here
        } else return false; // Just formalize the on to next iterator

    }

    // True if nodeName is of form int.int.other, could be one line, but how would you debug it.

    private boolean isEndNode( String nodeName ){
        int firstDot = nodeName.indexOf( '.' );
        if( firstDot <= 1 ) return false; // nodeName starts with '/' which is ignored
        int secondDot = nodeName.indexOf( '.', firstDot + 1 );
        if( secondDot <= 0 ) return false;
        String firstInt = nodeName.substring( 1, firstDot ); // nodeName starts with '/' which is ignored
        if( !firstInt.isInteger() ) return false;
        String secondInt = nodeName.substring( firstDot + 1, secondDot );
        if( secondInt.isInteger() ) return true;
        return false;
    }


    private boolean putInGroup( String path ) {
        Map<String, List<String>> props;
    //  stateSet.find { rec ->     // Find doesn't reset with next end node, back to basics
        for( rec in stateSet ) {
            ItemHandle folder = repo.folder( path );
            if( rec.state.size() > 0 ) {  // If this is not the last catch all category (empty string)
                props = folder.getProperties( rec.state );
            }
            if( rec.state.size() == 0 || props.size() > 0 ) { // If last entry or found match

                /* Create a record for this artifact. */

                PathAndDate nodePathDate = new PathAndDate();
                nodePathDate.path = path;
                nodePathDate.dtCreated = folder.info().lastModified;

                /* Create / Find branch specific grouping if has a branch */

                String sBranch = "";         // Assume no branch requested / found.
                if( rec.divider != null && rec.divider.size() > 0 ) {
                    Integer ndx = path.lastIndexOf( rec.divider );  // Do we have branch divider?
                    if( ndx >= 0 && ndx + rec.divider.size() < path.size() ) {
                        sBranch = path.substring(ndx + rec.divider.size());
                    }
                }
                Branch branch = rec.branches.find { it.branchName == sBranch };
                if( branch == null ) {
                      branch = new Branch( branchName: sBranch, pathAndDate: [] );
                      rec.branches.add( branch );
                }

                /* Now add this artifact into the right group */

                branch.pathAndDate.add( nodePathDate );  // process this one
                break;  // return true; // No other states matter if matched, break out of iterator
            } // else return false;  // On to next iterator

        }
        return true;                              // We always process all nodes which are end nodes
    }


    private boolean processSet() {
        for( state in stateSet ) {
            for( set in state.branches ) {
                int keep = state.cnt;
                if( keep == 0 || set.pathAndDate.size() < keep ) {  // If count to keep is zero, keep all
                    keep = set.pathAndDate.size()
                } else {
                    set.pathAndDate.sort() { a, b -> b.dtCreated <=> a.dtCreated };
                    // Sort newest first to preserve newest
                }
//                def now = new Date();
//                println 'Now is a ' + now.class.name + ' with value ' + now
//                println 'dtCreated is a ' + set.pathAndDate[ 0 ].dtCreated.class.name + ' with value ' + set.pathAndDate[ 0 ].dtCreated
//                Float diff = new Date() - set.pathAndDate[ 0 ].dtCreated;  // Compute age of artifact.
                while( keep > 0 ) {
//                    if( set.pathAndDate[ 0 ].dtCreated == null ) {
//                        println "Whatsup with the null?"
//                    }
                    if( state.maxDays > 0 && state.state != "" && set.branchName != "" && // Check if qualifies for maxDays
                        state.maxDays  < new Date() - set.pathAndDate[ 0 ].dtCreated ) { // Actual check for exceeding maxDays
                        numProcessed += processItem(set.pathAndDate[ 0 ].path);
                    }
                    set.pathAndDate.remove(0);
                    keep--;
                }
                while( set.pathAndDate.size() > 0 ) {
//                    if( set.pathAndDate[ 0 ].dtCreated == null ) {
//                        println "Whatsup with the null?"
                    def look = new Date() - set.pathAndDate[ 0 ].dtCreated;
                    if( state.minDays <= 0 ||            // Check if qualifies for minDays
                        state.minDays  < new Date() - set.pathAndDate[ 0 ].dtCreated ) { // Actual check for qualifying minDays
                        numProcessed += processItem(set.pathAndDate[0].path);
                    }
                    set.pathAndDate.remove(0);
                }
            }
        }
        return true;
    }

    private boolean processSize( String path ) {
//        org.jfrog.artifactory.client.impl.ItemHandleImpl folder = repo.folder( path );
        boolean isJar = path.endsWith( '.jar' );
        if( isJar  ) {
            org.jfrog.artifactory.client.impl.ItemHandleImpl item = repo.file( path );
            org.jfrog.artifactory.client.model.impl.FileImpl file = item.info();
            thisSize += file.size;
        }
    }

    private int processItem( String path ) {
        int retVal = 0;
        if( fullLog ) println "Processing folder: ${path}, ${function} with ${value}.";

        def RC;
        ItemHandle folder = repo.folder( path );
        Map<String, List<String>> props;
        boolean hasRqrd = true;
        if( !noValue( mustHave ) ) {
            props = folder.getProperties( mustHave );
            if( props.size() > 0 ) hasRqrd = true; else hasRqrd = false;  // I like this better than ternary operator, you?
        }
        if( !hasRqrd ) return retVal;

        switch( function ) {
            case 'delete':
                if( !dryRun ) RC = repo.delete( path );
                retVal++;
                break;

            case 'download':
                if( folder.isFolder() ) {
                    Folder item = folder.info();
                    item.children.find() { kid ->
                        if( kid.uri.endsWith('.jar') ) {
                            DownloadableArtifact DA = repo.download( path + kid.uri );
                            InputStream dlJar = DA.doDownload(); // Open Source
                            FileWriter lclJar = new FileWriter( targetDir + kid.uri, false ); // Open Dest
                            for( id in dlJar ) { lclJar.write( id ); } // Copy contents
                            if( fullLog ) println( "Downloaded ${path + kid.uri} to ${targetDir + kid.uri}." );
                            retVal++;
                        }
                    }
                }

                break;

            case 'mark':
                props = folder.getProperties( value );
                if( props.size() == 0 ) {
                    PropertiesHandler item = folder.properties();
                    PropertiesHandler PH = item.addProperty( value, 'true' );
                    if( !dryRun ) RC = PH.doSet();
                    retVal++;
                }
                break;

            case 'clear':
                 props = folder.getProperties( value );
                 if( props.size() == 1 ) {
                    if( !dryRun ) RC = folder.deleteProperty( value );  // Null return is success, go figure!
                    retVal++;
                }
                break;
            default:
                println( "Unknown function $function with $value encountered on ${path}.")

        }
    if( retVal > 0 ) println "Completed $function on $path with ${(value==null)?mustHave:value}.";
    return retVal;
    }
   private <T> T withClient( @ClosureParams( value = SimpleType, options = "org.jfrog.artifactory.client.Artifactory" ) Closure<T> closure ) {
        def client = ArtifactoryClient.create( "${webServer}artifactory", userName, password )
        try {
            return closure( client )

        } finally {
            client.close()
        }
    }
}
