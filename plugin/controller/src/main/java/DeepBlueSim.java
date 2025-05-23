import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.URISyntaxException;

import org.java_websocket.client.WebSocketClient;

import com.cyberbotics.webots.controller.Supervisor;

import edu.wpi.first.math.WPIMathJNI;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.NetworkTablesJNI;
import edu.wpi.first.util.CombinedRuntimeLoader;
import edu.wpi.first.util.WPIUtilJNI;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.carlmontrobotics.deepbluesim.SimRegisterer;
import org.carlmontrobotics.deepbluesim.Simulation;
import org.carlmontrobotics.deepbluesim.WebotsSupervisor;
import org.carlmontrobotics.wpiws.connection.RunningObject;

// NOTE: Webots expects the controller class to *not* be in a package and have a name that matches
// the name of the jar.
public class DeepBlueSim {
    // NOTE: By default, only messages at INFO level or higher are logged. To change that, if you
    // are using the default system logger, edit the the
    // Webots/controllers/DeepBlueSim/logging.properties file so that ".level=FINE".
    private static Logger LOG = null;

    private static void startNetworkTablesClient(NetworkTableInstance inst) {
        inst.startClient4("Webots controller");
        inst.setServer("localhost");
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.config.file",
                "logging.properties");
        LOG = System.getLogger(DeepBlueSim.class.getName());
        LOG.log(Level.DEBUG, "Starting log");
        // Set up exception handling to log to stderr and exit
        {
            UncaughtExceptionHandler eh = new UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread arg0, Throwable t) {
                    LOG.log(Level.ERROR,
                            "Uncaught exception! Here is the stacktrace:", t);
                    System.err.flush();
                    WebotsSupervisor.close();
                    System.exit(1);
                }
            };
            Thread.setDefaultUncaughtExceptionHandler(eh);
            Thread.currentThread().setUncaughtExceptionHandler(eh);
        }

        // Boilerplate code so we can use NetworkTables
        {
            NetworkTablesJNI.Helper.setExtractOnStaticLoad(false);
            WPIUtilJNI.Helper.setExtractOnStaticLoad(false);
            WPIMathJNI.Helper.setExtractOnStaticLoad(false);

            CombinedRuntimeLoader.loadLibraries(CombinedRuntimeLoader.class, "wpiutiljni",
                    "wpimathjni", "ntcorejni");
        }


        // Parse Arguments
        CommandLine cmd = parseCMDArgs(args);
        boolean connectToRobotCode = !cmd.hasOption("no-robot-code");

        final Supervisor robot = new Supervisor();
        Runtime.getRuntime().addShutdownHook(new Thread(robot::delete));

        if (!robot.getSupervisor()) {
            LOG.log(Level.ERROR,
                    "The robot does not have supervisor=true. This is required to detect devices.");
            System.exit(1);
        }

        // Get the basic timestep to use for calls to robot.step()
        final int basicTimeStep = (int) Math.round(robot.getBasicTimeStep());
        Simulation.init(robot, robot.getBasicTimeStep());

        // Connect to Network Tables
        if (!cmd.hasOption("no-network-tables")) {
            NetworkTableInstance inst = NetworkTableInstance.getDefault();
            startNetworkTablesClient(inst);
        }

        if (connectToRobotCode) {
            WebotsSupervisor.init(robot, basicTimeStep);
        }

        // Wait until startup has completed to ensure that the Webots simulator is
        // not still starting up.
        if (robot.step(0) == -1) {
            throw new RuntimeException("Couldn't even start up!");
        }

        SimRegisterer.connectDevices();


        if (connectToRobotCode) {
            // Pause the simulation until either the robot code tells us to proceed or the
            // user does.
            robot.simulationSetMode(Supervisor.SIMULATION_MODE_PAUSE);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // If connections haven't already been closed, we try to close them now. Note that
                // this might silently fail if it takes too long.
                WebotsSupervisor.close();
            }));
        }

        try {
            if (connectToRobotCode) {
                // Process incoming messages until simulation finishes
                WebotsSupervisor.runUntilTermination(robot, basicTimeStep);
            } else {
                // No robot code to synchronize against. Step the simulation and run periodic
                // methods until told to terminate
                while (robot.step(basicTimeStep) != -1) {
                    Simulation.runPeriodicMethods();
                }
            }
        } catch (Throwable ex) {
            LOG.log(Level.ERROR,
                    "Exception while waiting for simulation to be done:", ex);
            throw new RuntimeException(
                    "Exception while waiting for simulation to be done", ex);
        }

        LOG.log(Level.INFO, "Shutting down DeepBlueSim...");

        System.exit(0);
    }

    private static CommandLine parseCMDArgs(String[] args) {
        Options options = new Options();
        options.addOption("h", "help", false,
                "Display this help message and exit");
        options.addOption(null, "no-network-tables", false,
                "Do not attempt to connect to Network Tables");
        options.addOption(null, "no-robot-code", false,
                "Do not attempt to connect to the HALSim Server");

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        String cmdLineSyntax = "DeepBlueSim.jar [options]";

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                formatter.printHelp(cmdLineSyntax, options);
                System.exit(0);
            }

            return cmd;
        } catch (ParseException ex) {
            System.err.println(ex.getMessage());
            formatter.printHelp(cmdLineSyntax, options);
            System.exit(1);
            return null;
        }
    }
}
