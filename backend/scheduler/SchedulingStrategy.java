package backend.scheduler;

import backend.model.Chapter;
import backend.model.Exam;
import backend.model.StudySession;
import java.time.LocalDate;
import java.util.List;

public interface SchedulingStrategy {
    List<StudySession> generate(Exam exam, List<Chapter> chapters, LocalDate today);
}
