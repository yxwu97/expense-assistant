package expenseassistant;

import javafx.geometry.Rectangle2D;

import java.util.List;

final class StartupWindow {
    private StartupWindow() {}

    static int selectScreen(double x, double y, double width, double height, List<Rectangle2D> screens,
                            int primaryIndex) {
        double right = x + width;
        double bottom = y + height;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(right) || !Double.isFinite(bottom)
                || width <= 0 || height <= 0) {
            return primaryIndex;
        }
        int selected = primaryIndex;
        double largestArea = 0;
        for (int index = 0; index < screens.size(); index++) {
            var screen = screens.get(index);
            double overlapWidth = Math.max(0, Math.min(right, screen.getMaxX()) - Math.max(x, screen.getMinX()));
            double overlapHeight = Math.max(0, Math.min(bottom, screen.getMaxY()) - Math.max(y, screen.getMinY()));
            double area = overlapWidth * overlapHeight;
            if (area > largestArea || (area > 0 && area == largestArea && index == primaryIndex)) {
                selected = index;
                largestArea = area;
            }
        }
        return selected;
    }

    static Placement center(Rectangle2D visualBounds, double targetWidth, double targetHeight,
                            double minimumWidth, double minimumHeight) {
        double availableWidth = visualBounds.getWidth();
        double availableHeight = visualBounds.getHeight();
        double minWidth = Math.min(minimumWidth, availableWidth);
        double minHeight = Math.min(minimumHeight, availableHeight);
        double width = Math.min(Math.max(targetWidth, minWidth), availableWidth);
        double height = Math.min(Math.max(targetHeight, minHeight), availableHeight);
        double x = visualBounds.getMinX() + (availableWidth - width) / 2;
        double y = visualBounds.getMinY() + (availableHeight - height) / 2;
        return new Placement(x, y, width, height, minWidth, minHeight);
    }

    record Placement(double x, double y, double width, double height, double minWidth, double minHeight) {}
}
