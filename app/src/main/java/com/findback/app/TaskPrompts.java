package com.findback.app;

/** Image-grounded interpretations never enter the place-verification state machine. */
final class TaskPrompts {
    static String build(String mode,String observation){
        String task=switch(mode){
            case "find" -> "Locate the requested object in this image using the detector observation. State its camera-relative position, or that it is not clearly visible. Never invent a detection. ";
            case "read" -> "Read visible text using the OCR excerpt. Preserve exact wording where readable; explain unclear portions. Never invent missing text. ";
            case "place" -> "Explain visible washroom signage, distinguishing an entrance label from a direction board. Never confirm arrival or give walking directions. ";
            default -> throw new IllegalArgumentException("Unsupported review task");
        };
        String excerpt=observation==null?"":observation.replaceAll("\\s+"," ").trim();
        return task+"Observation: "+excerpt.substring(0,Math.min(excerpt.length(),256-task.length()-13));
    }
}
