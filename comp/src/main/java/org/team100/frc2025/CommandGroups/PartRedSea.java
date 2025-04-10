package org.team100.frc2025.CommandGroups;

import org.team100.frc2025.Climber.Climber;
import org.team100.frc2025.Climber.SetClimber;
import org.team100.frc2025.Elevator.Elevator;
import org.team100.frc2025.Elevator.SetElevator;
import org.team100.frc2025.Wrist.SetWrist;
import org.team100.frc2025.Wrist.SetWristDutyCycle;
import org.team100.frc2025.Wrist.SetWristHandoff;
import org.team100.frc2025.Wrist.Wrist2;
import org.team100.lib.framework.ParallelCommandGroup100;
import org.team100.lib.framework.ParallelRaceGroup100;
import org.team100.lib.framework.SequentialCommandGroup100;
import org.team100.lib.logging.LoggerFactory;

import edu.wpi.first.wpilibj2.command.WaitCommand;

public class PartRedSea extends SequentialCommandGroup100 {
    public PartRedSea(LoggerFactory logger, Wrist2 wrist, Elevator elevator, Climber climber) {
        super(logger, "PartRedSea");
        addCommands(
                new SetWristHandoff(wrist, 0.1),
                new SetWristDutyCycle(wrist, -0.11, true),
                new ParallelRaceGroup100(m_logger, "unclimb",
                        new WaitCommand(4),
                        new SetClimber(climber, -3)),
                new ParallelCommandGroup100(m_logger, "elevate",
                        new SetElevator(m_logger, elevator, 10, true),
                        new SetWrist(wrist, 0.5, true)));
    }
}
