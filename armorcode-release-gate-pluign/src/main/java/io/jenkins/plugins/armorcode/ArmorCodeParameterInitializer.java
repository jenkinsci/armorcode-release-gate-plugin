package io.jenkins.plugins.armorcode;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;

@Extension
public class ArmorCodeParameterInitializer {

    @Initializer(before = InitMilestone.PLUGINS_STARTED)
    public static void whitelistParameters() {
        // Get current safe parameters
        String currentParams = System.getProperty("hudson.model.ParametersAction.safeParameters", "");

        // Parameters to whitelist
        String armorCodeParams =
                "ArmorCode.GateUsed,ArmorCode.Product,ArmorCode.SubProducts,ArmorCode.Env,ArmorCode.GateResult";

        // Only add them if they're not already included
        if (!currentParams.contains(armorCodeParams)) {
            if (currentParams.isEmpty()) {
                System.setProperty("hudson.model.ParametersAction.safeParameters", armorCodeParams);
            } else {
                System.setProperty(
                        "hudson.model.ParametersAction.safeParameters", currentParams + "," + armorCodeParams);
            }
        }
    }
}