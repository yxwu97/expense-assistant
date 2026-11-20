package expenseassistant;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupWindowTest {
    private static final Rectangle2D PRIMARY = new Rectangle2D(0, 0, 1440, 900);
    private static final Rectangle2D LEFT = new Rectangle2D(-1280, 0, 1280, 800);
    private static final Rectangle2D RIGHT = new Rectangle2D(1440, -120, 1920, 1080);
    private static final List<Rectangle2D> SCREENS = List.of(LEFT, PRIMARY, RIGHT);

    @Test
    void centersOuterFramesInTheSelectedScreensUsableArea() {
        var visual = new Rectangle2D(-1600, 50, 1600, 820);
        assertEquals(new StartupWindow.Placement(-1110, 330, 620, 260, 0, 0),
                StartupWindow.center(visual, 620, 260, 0, 0));
        assertEquals(new StartupWindow.Placement(-1345, 80, 1090, 760, 520, 420),
                StartupWindow.center(visual, 1090, 760, 520, 420));
    }

    @Test
    void clampsTheTargetAndMinimumSizesIndependentlyOnSmallScreens() {
        var narrow = new Rectangle2D(1920, -300, 414, 720);
        assertEquals(new StartupWindow.Placement(1920, -300, 414, 720, 414, 420),
                StartupWindow.center(narrow, 1090, 760, 520, 420));
        var shortScreen = new Rectangle2D(100, 25, 800, 300);
        assertEquals(new StartupWindow.Placement(100, 25, 800, 300, 520, 300),
                StartupWindow.center(shortScreen, 1090, 760, 520, 420));
    }

    @Test
    void selectsTheScreenWithTheLargestIntersectionUsingFullBounds() {
        assertEquals(0, StartupWindow.selectScreen(-500, 80, 620, 260, SCREENS, 1));
        assertEquals(2, StartupWindow.selectScreen(1400, -80, 620, 260, SCREENS, 1));
        var lowLeft = new Rectangle2D(-1280, 230, 1280, 570);
        assertEquals(1, StartupWindow.selectScreen(-400, 90, 620, 260, List.of(lowLeft, PRIMARY), 1));
    }

    @Test
    void breaksEqualIntersectionsByPrimaryScreenThenListOrder() {
        assertEquals(1, StartupWindow.selectScreen(-310, 90, 620, 260, SCREENS, 1));
        assertEquals(0, StartupWindow.selectScreen(-310, 90, 620, 260, SCREENS, 2));
    }

    @Test
    void fallsBackToPrimaryWhenTheFrameIsInvalidOrOutsideTheScreens() {
        assertEquals(1, StartupWindow.selectScreen(-2000, 90, 620, 260, SCREENS, 1));
        assertEquals(1, StartupWindow.selectScreen(-1900, 90, 620, 260, SCREENS, 1));
        assertEquals(1, StartupWindow.selectScreen(Double.NaN, 90, 620, 260, SCREENS, 1));
        assertEquals(1, StartupWindow.selectScreen(-500, 90, Double.POSITIVE_INFINITY, 260, SCREENS, 1));
        assertEquals(1, StartupWindow.selectScreen(-500, 90, 620, 0, SCREENS, 1));
    }
}
