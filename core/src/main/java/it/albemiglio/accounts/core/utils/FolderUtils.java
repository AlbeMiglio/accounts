package it.albemiglio.accounts.core.utils;

import it.albemiglio.accounts.core.objects.enums.Platform;

import java.io.File;
import java.net.URISyntaxException;

public class FolderUtils {

    private static File serverFolder;

    static {
        try {
            serverFolder = new File(Platform.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
