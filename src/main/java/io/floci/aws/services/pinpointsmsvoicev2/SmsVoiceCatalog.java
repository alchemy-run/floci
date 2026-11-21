package io.floci.aws.services.pinpointsmsvoicev2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What SMS and Voice knows about the world: countries and their dialing codes, the number types each supports, prices,
 * registration types with their sections and fields, and the Notify templates and countries.
 */
final class SmsVoiceCatalog {

    /** A country: its ISO code, name, dialing code and per-message prices. */
    record Country(String iso, String name, String dial, double textPrice, double voicePrice) {
    }

    static final Map<String, Country> COUNTRIES = new LinkedHashMap<>();

    static {
        for (Country country : List.of(
                new Country("US", "United States", "1", 0.00581, 0.013),
                new Country("CA", "Canada", "1", 0.00675, 0.013),
                new Country("GB", "United Kingdom", "44", 0.0385, 0.0218),
                new Country("DE", "Germany", "49", 0.0911, 0.0294),
                new Country("FR", "France", "33", 0.0688, 0.0294),
                new Country("ES", "Spain", "34", 0.0798, 0.0294),
                new Country("IT", "Italy", "39", 0.0802, 0.0294),
                new Country("NL", "Netherlands", "31", 0.0996, 0.0294),
                new Country("IN", "India", "91", 0.0018, 0.0381),
                new Country("AU", "Australia", "61", 0.0410, 0.0294),
                new Country("JP", "Japan", "81", 0.0757, 0.0838),
                new Country("BR", "Brazil", "55", 0.0221, 0.0430),
                new Country("MX", "Mexico", "52", 0.0193, 0.0243),
                new Country("SG", "Singapore", "65", 0.0344, 0.0280),
                new Country("ZA", "South Africa", "27", 0.0189, 0.0394))) {
            COUNTRIES.put(country.iso(), country);
        }
    }

    /** NANP area codes that are Canadian. */
    private static final Set<String> CANADA = Set.of("204", "226", "236", "249", "250", "263", "289", "306", "343",
            "354", "365", "367", "368", "382", "403", "416", "418", "428", "431", "437", "438", "450", "468", "474",
            "506", "514", "519", "548", "579", "581", "584", "587", "604", "613", "639", "647", "672", "683", "705",
            "709", "742", "753", "778", "780", "782", "807", "819", "825", "867", "873", "879", "902", "905");

    /** NANP toll-free area codes. */
    static final Set<String> TOLL_FREE = Set.of("800", "833", "844", "855", "866", "877", "888");

    private SmsVoiceCatalog() {
    }

    /** Fails unless a number is in E.164 form. */
    static String e164(String number, String member) {
        if (number == null || !number.matches("\\+[1-9][0-9]{6,14}")) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", member + " must be a phone number in E.164 format, "
                    + "such as +12065550100.");
        }
        return number;
    }

    /** The country a number dials, or null when Floci doesn't know it. */
    static Country countryOf(String number) {
        if (number == null || !number.startsWith("+")) {
            return null;
        }
        String digits = number.substring(1);
        if (digits.startsWith("1") && digits.length() >= 4) {
            return COUNTRIES.get(CANADA.contains(digits.substring(1, 4)) ? "CA" : "US");
        }
        Country best = null;
        for (Country country : COUNTRIES.values()) {
            if (digits.startsWith(country.dial()) && (best == null || country.dial().length() > best.dial().length())) {
                best = country;
            }
        }
        return best;
    }

    static Country country(String iso) {
        Country country = COUNTRIES.get(iso);
        if (country == null) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", "Floci doesn't sell numbers in " + iso + ".");
        }
        return country;
    }

    /** The monthly lease of a number type. */
    static String leasePrice(String type) {
        return switch (type) {
            case "SIMULATOR" -> "0.00";
            case "TOLL_FREE" -> "2.00";
            case "SHORT_CODE" -> "995.00";
            default -> "1.00";
        };
    }

    /** A fresh number of a type in a country. */
    static String newNumber(String type, Country country) {
        String random = String.format("%07d", SmsVoiceStore.randomInt(10_000_000));
        if (country.dial().equals("1")) {
            return switch (type) {
                case "SIMULATOR" -> "+1206555" + random.substring(3);
                case "TOLL_FREE" -> "+1833" + random;
                default -> "+1" + (country.iso().equals("CA") ? "604" : "425") + random;
            };
        }
        return "+" + country.dial() + "7" + random + SmsVoiceStore.randomInt(10);
    }

    /** Fails unless a country offers a number type. */
    static void checkNumberType(String type, Country country) {
        boolean ok = switch (type) {
            case "SIMULATOR", "TEN_DLC" -> country.iso().equals("US");
            case "TOLL_FREE" -> country.iso().equals("US") || country.iso().equals("CA");
            case "LONG_CODE" -> !country.iso().equals("US");
            default -> false;
        };
        if (!ok) {
            throw SmsVoiceStore.validation("INVALID_PARAMETER", type + " numbers aren't offered in "
                    + country.name() + ".");
        }
    }

    /** The GSM 03.38 basic character set (and its extension table). */
    private static final String GSM = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà^{}\\[~]|€";

    static boolean gsm(String body) {
        return body.chars().allMatch(c -> GSM.indexOf(c) >= 0);
    }

    /** How many parts a text message is sent in. */
    static int parts(String body) {
        boolean gsm = gsm(body);
        int single = gsm ? 160 : 70;
        int multi = gsm ? 153 : 67;
        int length = body.codePointCount(0, body.length());
        return length <= single ? 1 : (length + multi - 1) / multi;
    }

    // ================================================================ registrations

    /** A registration field. */
    record Field(String section, String path, String type, String requirement, List<String> options, int maxLength) {
        String fullPath() {
            return section + "." + path;
        }
    }

    /** A registration type: its title, what it associates with, and its fields. */
    record RegistrationType(String name, String title, String resourceType, String iso, String association,
                            List<Field> fields) {
    }

    private static final List<Field> COMPANY = List.of(
            new Field("companyInfo", "companyName", "TEXT", "REQUIRED", List.of(), 100),
            new Field("companyInfo", "website", "TEXT", "REQUIRED", List.of(), 500),
            new Field("companyInfo", "address1", "TEXT", "REQUIRED", List.of(), 100),
            new Field("companyInfo", "city", "TEXT", "REQUIRED", List.of(), 100),
            new Field("companyInfo", "isoCountryCode", "TEXT", "REQUIRED", List.of(), 2),
            new Field("contactInfo", "firstName", "TEXT", "REQUIRED", List.of(), 100),
            new Field("contactInfo", "lastName", "TEXT", "REQUIRED", List.of(), 100),
            new Field("contactInfo", "supportEmail", "TEXT", "REQUIRED", List.of(), 100),
            new Field("contactInfo", "supportPhoneNumber", "TEXT", "REQUIRED", List.of(), 20));

    private static final List<String> VOLUMES = List.of("10", "100", "1,000", "10,000", "100,000", "250,000",
            "500,000", "750,000", "1,000,000", "5,000,000", "10,000,000+");

    static final Map<String, RegistrationType> REGISTRATION_TYPES = new LinkedHashMap<>();

    static {
        List<Field> tollFree = new ArrayList<>(COMPANY);
        tollFree.addAll(List.of(
                new Field("messagingUseCase", "monthlyMessageVolume", "SELECT", "REQUIRED", VOLUMES, 0),
                new Field("messagingUseCase", "useCaseCategory", "SELECT", "REQUIRED", List.of("TWO_FACTOR_AUTHENTICATION",
                        "ACCOUNT_NOTIFICATIONS", "CUSTOMER_CARE", "DELIVERY_NOTIFICATIONS", "MARKETING", "POLLING_VOTING"), 0),
                new Field("messagingUseCase", "useCaseDescription", "TEXT", "REQUIRED", List.of(), 500),
                new Field("messageSamples", "messageSample1", "TEXT", "REQUIRED", List.of(), 1024),
                new Field("messageSamples", "messageSample2", "TEXT", "OPTIONAL", List.of(), 1024),
                new Field("optInDetails", "optInDescription", "TEXT", "REQUIRED", List.of(), 500),
                new Field("optInDetails", "optInImage", "ATTACHMENT", "REQUIRED", List.of(), 0)));
        List<Field> brand = new ArrayList<>(COMPANY);
        brand.addAll(List.of(
                new Field("companyInfo", "taxIdIssuingCountry", "TEXT", "REQUIRED", List.of(), 2),
                new Field("companyInfo", "taxId", "TEXT", "REQUIRED", List.of(), 21),
                new Field("companyInfo", "legalType", "SELECT", "REQUIRED", List.of("PRIVATE_PROFIT", "PUBLIC_PROFIT",
                        "NON_PROFIT", "GOVERNMENT"), 0)));
        List<Field> campaign = List.of(
                new Field("campaignInfo", "brandRegistrationId", "TEXT", "REQUIRED", List.of(), 64),
                new Field("campaignInfo", "campaignName", "TEXT", "REQUIRED", List.of(), 64),
                new Field("campaignInfo", "campaignDescription", "TEXT", "REQUIRED", List.of(), 4096),
                new Field("campaignUseCase", "useCase", "SELECT", "REQUIRED", List.of("TWO_FACTOR_AUTHENTICATION",
                        "ACCOUNT_NOTIFICATION", "CUSTOMER_CARE", "DELIVERY_NOTIFICATION", "MARKETING", "MIXED"), 0),
                new Field("messageSamples", "messageSample1", "TEXT", "REQUIRED", List.of(), 1024),
                new Field("campaignCapabilities", "numberCapability", "SELECT", "REQUIRED", List.of("SMS", "VOICE",
                        "MMS"), 0));
        List<Field> senderId = List.of(
                new Field("companyInfo", "companyName", "TEXT", "REQUIRED", List.of(), 100),
                new Field("companyInfo", "website", "TEXT", "REQUIRED", List.of(), 500),
                new Field("senderIdInfo", "senderId", "TEXT", "REQUIRED", List.of(), 11),
                new Field("messageSamples", "messageSample1", "TEXT", "REQUIRED", List.of(), 1024));
        for (RegistrationType type : List.of(
                new RegistrationType("US_TOLL_FREE_REGISTRATION", "US toll-free number registration", "TOLL_FREE",
                        "US", "ASSOCIATE_BEFORE_SUBMIT", tollFree),
                new RegistrationType("US_TEN_DLC_BRAND_REGISTRATION", "US 10DLC brand registration", null, "US", null,
                        brand),
                new RegistrationType("US_TEN_DLC_CAMPAIGN_REGISTRATION", "US 10DLC campaign registration", "TEN_DLC",
                        "US", "ASSOCIATE_ON_APPROVAL", campaign),
                new RegistrationType("GB_SENDER_ID_REGISTRATION", "United Kingdom sender ID registration",
                        "SENDER_ID", "GB", "ASSOCIATE_BEFORE_SUBMIT", senderId))) {
            REGISTRATION_TYPES.put(type.name(), type);
        }
    }

    static RegistrationType registrationType(String name) {
        RegistrationType type = REGISTRATION_TYPES.get(name);
        if (type == null) {
            throw SmsVoiceStore.validation("UNKNOWN_REGISTRATION_TYPE", "The registration type " + name
                    + " isn't known.");
        }
        return type;
    }

    static String title(String camel) {
        String spaced = camel.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    // ================================================================ Notify

    /** A Notify template. */
    record Template(String id, String language, String content, List<String> channels, List<String> tiers) {
    }

    static final Map<String, Template> TEMPLATES = new LinkedHashMap<>();

    static {
        for (Template template : List.of(
                new Template("OTP_EN_US_V1", "EN_US",
                        "Your {{brand}} verification code is {{code}}. It expires in {{expiry}} minutes.",
                        List.of("SMS", "VOICE"), List.of("BASIC", "ADVANCED")),
                new Template("OTP_ES_419_V1", "ES_419",
                        "Tu código de verificación de {{brand}} es {{code}}. Vence en {{expiry}} minutos.",
                        List.of("SMS", "VOICE"), List.of("BASIC", "ADVANCED")),
                new Template("OTP_FR_FR_V1", "FR_FR",
                        "Votre code de vérification {{brand}} est {{code}}. Il expire dans {{expiry}} minutes.",
                        List.of("SMS"), List.of("ADVANCED")))) {
            TEMPLATES.put(template.id(), template);
        }
    }

    /** The countries Notify sends to without an origination identity of the customer's. */
    static final Set<String> NOTIFY_BASIC = Set.of("US", "CA", "GB", "DE", "FR", "ES", "IT", "NL", "AU");
}
