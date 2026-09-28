import com.dshmobile.app.HarnessOverlay;

import java.io.File;

/**
 * Host-side runner for the device overlay logic, driven by
 * dsh-mobile/tools/verify-harness-overlay.mjs.
 *
 * Usage: java OverlayCheck &lt;rootfsDir&gt; &lt;overlayDir&gt;
 *
 * The output is machine-parsed, so it stays ASCII with explicit markers.
 */
public final class OverlayCheck {

    private OverlayCheck() {
    }

    public static void main(String[] args) {
        File rootfs = new File(args[0]);
        File overlay = new File(args[1]);
        System.out.println("NEEDS_BEFORE=" + HarnessOverlay.needsApply(rootfs, overlay));
        System.out.println("SUMMARY_BEGIN");
        System.out.println(HarnessOverlay.apply(rootfs, overlay));
        System.out.println("SUMMARY_END");
        System.out.println("NEEDS_AFTER=" + HarnessOverlay.needsApply(rootfs, overlay));
    }
}
