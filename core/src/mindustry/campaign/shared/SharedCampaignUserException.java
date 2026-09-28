package mindustry.campaign.shared;

/**
 * Expected Shared Campaign operation failure that should be presented as a normal user-facing message rather than a
 * Java exception dialog. The marker survives the control protocol, where remote failures are reconstructed as IOExceptions.
 */
public class SharedCampaignUserException extends RuntimeException{
    private static final String marker = "MINDUSTRYY-USER:";

    public SharedCampaignUserException(String message){
        super(marker + (message == null ? "Shared Campaign operation could not be completed" : message));
    }

    public static boolean isUserFacing(Throwable error){
        for(Throwable current = error; current != null; current = current.getCause()){
            String message = current.getMessage();
            if(message != null && message.contains(marker)) return true;
        }
        return false;
    }

    public static String displayMessage(Throwable error){
        for(Throwable current = error; current != null; current = current.getCause()){
            String message = current.getMessage();
            if(message == null) continue;
            int index = message.indexOf(marker);
            if(index >= 0) return message.substring(index + marker.length()).trim();
        }
        return error == null || error.getMessage() == null ? "Shared Campaign operation could not be completed" : error.getMessage();
    }
}
