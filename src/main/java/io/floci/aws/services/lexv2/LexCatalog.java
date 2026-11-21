package io.floci.aws.services.lexv2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Lex V2 offers in every account: the locales a bot can speak, the built-in intents (with the phrases they
 * recognize) and the built-in slot types.
 */
final class LexCatalog {

    static final Map<String, String> LOCALES = new LinkedHashMap<>();
    static final Map<String, String> BUILT_IN_INTENTS = new LinkedHashMap<>();
    static final Map<String, List<String>> BUILT_IN_UTTERANCES = new LinkedHashMap<>();
    static final Map<String, String> BUILT_IN_SLOT_TYPES = new LinkedHashMap<>();

    static {
        LOCALES.put("ar_AE", "Arabic (Gulf)");
        LOCALES.put("ca_ES", "Catalan (Spain)");
        LOCALES.put("cs_CZ", "Czech (Czech Republic)");
        LOCALES.put("cy_GB", "Welsh (United Kingdom)");
        LOCALES.put("da_DK", "Danish (Denmark)");
        LOCALES.put("de_AT", "German (Austria)");
        LOCALES.put("de_CH", "German (Switzerland)");
        LOCALES.put("de_DE", "German (Germany)");
        LOCALES.put("en_AU", "English (AU)");
        LOCALES.put("en_GB", "English (GB)");
        LOCALES.put("en_IE", "English (IE)");
        LOCALES.put("en_IN", "English (IN)");
        LOCALES.put("en_NZ", "English (NZ)");
        LOCALES.put("en_US", "English (US)");
        LOCALES.put("en_ZA", "English (ZA)");
        LOCALES.put("es_419", "Spanish (Latin America)");
        LOCALES.put("es_ES", "Spanish (Spain)");
        LOCALES.put("es_US", "Spanish (US)");
        LOCALES.put("fi_FI", "Finnish (Finland)");
        LOCALES.put("fr_CA", "French (Canada)");
        LOCALES.put("fr_FR", "French (France)");
        LOCALES.put("gsw_CH", "Swiss German (Switzerland)");
        LOCALES.put("hi_IN", "Hindi (India)");
        LOCALES.put("is_IS", "Icelandic (Iceland)");
        LOCALES.put("it_IT", "Italian (Italy)");
        LOCALES.put("ja_JP", "Japanese (Japan)");
        LOCALES.put("ko_KR", "Korean (Korea)");
        LOCALES.put("nl_NL", "Dutch (Netherlands)");
        LOCALES.put("no_NO", "Norwegian (Norway)");
        LOCALES.put("pl_PL", "Polish (Poland)");
        LOCALES.put("pt_BR", "Portuguese (Brazil)");
        LOCALES.put("pt_PT", "Portuguese (Portugal)");
        LOCALES.put("ro_RO", "Romanian (Romania)");
        LOCALES.put("sv_SE", "Swedish (Sweden)");
        LOCALES.put("tr_TR", "Turkish (Turkey)");
        LOCALES.put("yue_HK", "Cantonese (Hong Kong)");
        LOCALES.put("zh_CN", "Mandarin (PRC)");

        intent("AMAZON.BedrockAgentIntent", "Answers questions with an Amazon Bedrock agent or knowledge base.");
        intent("AMAZON.CancelIntent", "Responds to words and phrases that indicate the user wants to cancel the "
                + "current interaction.", "cancel", "never mind", "forget it", "cancel that");
        intent("AMAZON.FallbackIntent", "Default intent when no other intent matches.");
        intent("AMAZON.HelpIntent", "Responds to words or phrases that indicate the user needs help.", "help",
                "help me", "can you help me", "i need help");
        intent("AMAZON.KendraSearchIntent", "Searches an Amazon Kendra index for an answer.");
        intent("AMAZON.PauseIntent", "Responds to words and phrases that enable the user to pause.", "pause",
                "pause that", "hold on");
        intent("AMAZON.QInConnectIntent", "Answers questions with Amazon Q in Connect.");
        intent("AMAZON.QnAIntent", "Answers questions from a knowledge store with generative AI.");
        intent("AMAZON.RepeatIntent", "Responds to words and phrases that enable the user to repeat the previous "
                + "message.", "repeat", "say that again", "repeat that", "come again");
        intent("AMAZON.ResumeIntent", "Responds to words and phrases that enable the user to resume.", "resume",
                "continue", "keep going");
        intent("AMAZON.StartOverIntent", "Responds to words and phrases that enable the user to start over.",
                "start over", "restart", "start again");
        intent("AMAZON.StopIntent", "Responds to words and phrases that indicate the user wants to stop.", "stop",
                "off", "shut up", "be quiet");

        slotType("AMAZON.AlphaNumeric", "Recognizes words that are made up of letters and numbers.");
        slotType("AMAZON.City", "Recognizes words that represent a city.");
        slotType("AMAZON.Confirmation", "Recognizes words that mean yes, no, maybe and don't know.");
        slotType("AMAZON.Country", "Recognizes words that represent a country.");
        slotType("AMAZON.Date", "Converts words that represent dates into a date format.");
        slotType("AMAZON.Duration", "Converts words that indicate durations into a numeric duration.");
        slotType("AMAZON.EmailAddress", "Recognizes words that represent an email address.");
        slotType("AMAZON.FirstName", "Recognizes commonly used first names.");
        slotType("AMAZON.FreeFormInput", "Recognizes strings that consist of any words or characters.");
        slotType("AMAZON.LastName", "Recognizes commonly used last names.");
        slotType("AMAZON.Number", "Converts words or numbers that express a number into digits.");
        slotType("AMAZON.Percentage", "Converts words and symbols that represent a percentage into a number.");
        slotType("AMAZON.PhoneNumber", "Converts the numbers or words that represent a phone number into a string.");
        slotType("AMAZON.State", "Recognizes words that represent a state.");
        slotType("AMAZON.StreetName", "Recognizes words that represent a street name.");
        slotType("AMAZON.Time", "Converts words that represent times into time values.");
        slotType("AMAZON.UKPostalCode", "Converts words that represent a UK post code into a standard form.");
    }

    private LexCatalog() {
    }

    private static void intent(String signature, String description, String... utterances) {
        BUILT_IN_INTENTS.put(signature, description);
        BUILT_IN_UTTERANCES.put(signature, List.of(utterances));
    }

    private static void slotType(String signature, String description) {
        BUILT_IN_SLOT_TYPES.put(signature, description);
    }

    static void checkLocale(String localeId) {
        if (localeId == null || !LOCALES.containsKey(localeId)) {
            throw LexStore.validation("The locale " + localeId + " is not supported.");
        }
    }
}
