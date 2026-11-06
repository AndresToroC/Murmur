package com.nochesonora.core.classification

/**
 * Tipo de sonido "amigable" para mostrar (perro, puerta, lluvia…). Es solo presentación: la
 * categoría de cada evento (RONQUIDO / HABLA / TOS / OTRO / FONDO) la siguen decidiendo
 * [LabelMapper] y [VotingClassifier]; esto agrupa las 521 etiquetas de YAMNet para nombrarlas.
 */
enum class SoundType {
    DOG, CAT, ANIMAL, BABY, MOAN, LAUGHTER, VOICES,
    SNORING, BREATHING, COUGH, SNEEZE, THROAT, BODY,
    MOVEMENT, IMPACT, DOOR, FOOTSTEPS,
    ALARM, SIREN, VEHICLE, FIREWORKS,
    RAIN, WIND, WATER, MUSIC_TV, HOUSEHOLD, FAN, ROOM,
}

/** Etiqueta YAMNet (nombre exacto de `yamnet_label_list.txt`) → [SoundType]. */
object SoundTypes {
    private val table: Map<String, SoundType> = buildMap {
        fun put(type: SoundType, vararg labels: String) = labels.forEach { put(it, type) }

        put(SoundType.DOG, "Dog", "Bark", "Yip", "Howl", "Bow-wow", "Growling", "Whimper (dog)", "Canidae, dogs, wolves")
        put(SoundType.CAT, "Cat", "Purr", "Meow", "Hiss", "Caterwaul")
        put(
            SoundType.ANIMAL,
            "Animal", "Domestic animals, pets", "Livestock, farm animals, working animals", "Horse", "Clip-clop",
            "Neigh, whinny", "Cattle, bovinae", "Moo", "Pig", "Oink", "Goat", "Bleat", "Sheep", "Fowl",
            "Chicken, rooster", "Cluck", "Crowing, cock-a-doodle-doo", "Turkey", "Gobble", "Duck", "Quack", "Goose",
            "Honk", "Wild animals", "Roaring cats (lions, tigers)", "Roar", "Bird", "Bird vocalization, bird call, bird song",
            "Chirp, tweet", "Squawk", "Pigeon, dove", "Coo", "Crow", "Caw", "Owl", "Hoot", "Bird flight, flapping wings",
            "Rodents, rats, mice", "Mouse", "Patter", "Insect", "Cricket", "Mosquito", "Fly, housefly", "Buzz",
            "Bee, wasp, etc.", "Frog", "Croak", "Snake", "Rattle", "Cowbell", "Whale vocalization",
        )
        put(SoundType.BABY, "Baby cry, infant cry", "Baby laughter")
        put(SoundType.MOAN, "Crying, sobbing", "Whimper", "Wail, moan", "Groan", "Grunt")
        put(SoundType.LAUGHTER, "Laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle")
        put(
            SoundType.VOICES,
            "Speech", "Child speech, kid speaking", "Conversation", "Narration, monologue", "Babbling", "Speech synthesizer",
            "Shout", "Bellow", "Whoop", "Yell", "Children shouting", "Screaming", "Whispering", "Singing", "Child singing",
            "Humming", "Whistling", "Synthetic singing", "Chatter", "Crowd", "Hubbub, speech noise, speech babble", "Children playing",
            "Cheering", "Applause",
        )
        put(SoundType.SNORING, "Snoring", "Snort")
        put(SoundType.BREATHING, "Breathing", "Wheeze", "Gasp", "Pant", "Sigh", "Sniff")
        put(SoundType.COUGH, "Cough")
        put(SoundType.SNEEZE, "Sneeze")
        put(SoundType.THROAT, "Throat clearing", "Gargling")
        put(
            SoundType.BODY,
            "Chewing, mastication", "Biting", "Stomach rumble", "Burping, eructation", "Hiccup", "Fart",
            "Heart sounds, heartbeat", "Heart murmur", "Hands", "Finger snapping", "Clapping",
        )
        put(
            SoundType.MOVEMENT,
            "Rustle", "Rub", "Scratch", "Scrape", "Crumpling, crinkling", "Tearing", "Flap", "Zipper (clothing)",
            "Roll", "Squish", "Shuffle",
        )
        put(
            SoundType.IMPACT,
            "Thump, thud", "Thunk", "Bang", "Slap, smack", "Whack, thwack", "Smash, crash", "Breaking", "Bouncing",
            "Tap", "Clatter", "Crack", "Shatter", "Chink, clink", "Clang", "Wood", "Chop", "Splinter", "Glass", "Plop",
            "Crunch", "Crushing", "Basketball bounce",
        )
        put(
            SoundType.DOOR,
            "Door", "Doorbell", "Ding-dong", "Sliding door", "Slam", "Knock", "Squeak", "Creak",
            "Cupboard open or close", "Drawer open or close", "Keys jangling",
        )
        put(SoundType.FOOTSTEPS, "Walk, footsteps", "Run")
        put(
            SoundType.ALARM,
            "Alarm", "Telephone", "Telephone bell ringing", "Ringtone", "Telephone dialing, DTMF", "Dial tone",
            "Busy signal", "Alarm clock", "Buzzer", "Smoke detector, smoke alarm", "Fire alarm", "Beep, bleep", "Ping",
            "Ding", "Bell", "Bicycle bell", "Jingle bell", "Chime", "Wind chime", "Church bell",
        )
        put(
            SoundType.SIREN,
            "Siren", "Civil defense siren", "Police car (siren)", "Ambulance (siren)", "Fire engine, fire truck (siren)",
            "Emergency vehicle", "Foghorn",
        )
        put(
            SoundType.VEHICLE,
            "Vehicle", "Boat, Water vehicle", "Sailboat, sailing ship", "Rowboat, canoe, kayak", "Motorboat, speedboat",
            "Ship", "Motor vehicle (road)", "Car", "Vehicle horn, car horn, honking", "Toot", "Car alarm",
            "Power windows, electric windows", "Skidding", "Tire squeal", "Car passing by", "Race car, auto racing", "Truck",
            "Air brake", "Air horn, truck horn", "Reversing beeps", "Ice cream truck, ice cream van", "Bus", "Motorcycle",
            "Traffic noise, roadway noise", "Rail transport", "Train", "Train whistle", "Train horn",
            "Railroad car, train wagon", "Train wheels squealing", "Subway, metro, underground", "Aircraft",
            "Aircraft engine", "Jet engine", "Propeller, airscrew", "Helicopter", "Fixed-wing aircraft, airplane",
            "Bicycle", "Skateboard", "Engine", "Light engine (high frequency)", "Medium engine (mid frequency)",
            "Heavy engine (low frequency)", "Engine knocking", "Engine starting", "Idling",
            "Accelerating, revving, vroom", "Lawn mower", "Chainsaw",
        )
        put(
            SoundType.FIREWORKS,
            "Explosion", "Gunshot, gunfire", "Machine gun", "Fusillade", "Artillery fire", "Cap gun", "Fireworks",
            "Firecracker", "Burst, pop", "Eruption", "Boom",
        )
        put(SoundType.RAIN, "Rain", "Raindrop", "Rain on surface", "Thunderstorm", "Thunder")
        put(SoundType.WIND, "Wind", "Rustling leaves", "Wind noise (microphone)", "Whoosh, swoosh, swish")
        put(
            SoundType.WATER,
            "Water", "Stream", "Waterfall", "Ocean", "Waves, surf", "Steam", "Gurgling", "Water tap, faucet",
            "Sink (filling or washing)", "Bathtub (filling or washing)", "Toilet flush", "Liquid", "Splash, splatter",
            "Slosh", "Drip", "Pour", "Trickle, dribble", "Gush", "Fill (with liquid)", "Spray", "Pump (liquid)", "Stir",
            "Boiling",
        )
        put(
            SoundType.HOUSEHOLD,
            "Dishes, pots, and pans", "Cutlery, silverware", "Chopping (food)", "Frying (food)", "Microwave oven",
            "Blender", "Hair dryer", "Toothbrush", "Electric toothbrush", "Vacuum cleaner", "Coin (dropping)", "Scissors",
            "Electric shaver, electric razor", "Shuffling cards", "Typing", "Typewriter", "Computer keyboard", "Writing",
            "Mechanisms", "Ratchet, pawl", "Clock", "Tick", "Tick-tock", "Gears", "Pulleys", "Sewing machine",
            "Cash register", "Printer", "Camera", "Single-lens reflex camera", "Tools", "Hammer", "Jackhammer", "Sawing",
            "Filing (rasp)", "Sanding", "Power tool", "Drill", "Dental drill, dentist's drill", "Whir", "Clicking",
            "Clickety-clack", "Sizzle", "Fire", "Crackle", "Jingle, tinkle",
        )
        put(SoundType.FAN, "Mechanical fan", "Air conditioning")
        put(
            SoundType.ROOM,
            "Inside, small room", "Inside, large room or hall", "Inside, public space", "Outside, urban or manmade",
            "Outside, rural or natural", "Reverberation", "Echo", "Noise", "Environmental noise", "Hum", "Mains hum",
            "Static", "White noise", "Pink noise", "Rumble", "Vibration", "Throbbing", "Field recording",
        )
    }

    /** Toda la música (géneros e instrumentos), la TV y la radio. */
    private val musicTv: Set<String> = setOf("Television", "Radio", "Song", "Background music", "Theme music")

    /** Palabras que delatan una etiqueta musical de YAMNet (hay ~150: géneros, instrumentos…). */
    private val musicHints = listOf(
        "music", "Music", "guitar", "Guitar", "piano", "Piano", "organ", "Organ", "drum", "Drum", "instrument",
        "Choir", "Yodeling", "Chant", "Mantra", "Rapping", "Synthesizer", "Sampler", "Harpsichord", "Percussion",
        "Cymbal", "Hi-hat", "Tambourine", "Maraca", "Gong", "bells", "Marimba", "Glockenspiel", "Vibraphone", "Steelpan",
        "Orchestra", "French horn", "Trumpet", "Trombone", "String section", "Violin", "Pizzicato", "Cello",
        "Double bass", "Flute", "Saxophone", "Clarinet", "Harp", "Tuning fork", "Change ringing", "Harmonica",
        "Accordion", "Bagpipes", "Didgeridoo", "Shofar", "Theremin", "Singing bowl", "Scratching (performance",
        "Beatboxing", "Rock", "rock", "Heavy metal", "Punk", "Grunge", "Rhythm and blues", "Reggae", "Country", "Bluegrass",
        "Funk", "Jazz", "Disco", "Opera", "House music", "Techno", "Dubstep", "Electronica", "Trance", "Salsa",
        "Flamenco", "Blues", "A capella", "Afrobeat", "Gospel", "Carnatic", "Bollywood", "Ska", "Jingle (music)",
        "Lullaby", "Banjo", "Sitar", "Mandolin", "Zither", "Ukulele", "Keyboard (musical)", "Tabla", "Timpani",
        "Rimshot", "Wood block", "Tapping (guitar", "Strum", "Mallet percussion", "Tubular bells", "Wind instrument",
        "Bowed string", "Brass instrument", "Electronic tuner", "Effects unit", "Chorus effect",
    )

    /** Etiquetas que no dicen nada del sonido (sin clasificar o desconocido). */
    private val meaningless = setOf(LabelMapper.UNKNOWN_LABEL, "Sin clasificar", "Silence", "Sound effect", "Cacophony")

    /** Para el test: todas las etiquetas de la tabla deben existir en YAMNet (sin erratas). */
    internal val mappedLabels: Set<String> get() = table.keys + musicTv

    fun of(label: String?): SoundType? {
        if (label.isNullOrBlank()) return null
        table[label]?.let { return it }
        if (label in musicTv || musicHints.any { label.contains(it) }) return SoundType.MUSIC_TV
        return null
    }

    /**
     * Tipo de un evento: primero su etiqueta OTRO; si no dice nada, la primera de sus etiquetas
     * principales que tenga tipo (sin contar las del fondo del cuarto).
     */
    fun ofEvent(otherLabel: String?, topLabels: List<String>): SoundType? {
        if (otherLabel != null && otherLabel !in meaningless) of(otherLabel)?.let { return it }
        return topLabels.asSequence()
            .filter { it !in meaningless }
            .mapNotNull(::of)
            .firstOrNull { it != SoundType.ROOM && it != SoundType.FAN }
            ?: of(otherLabel)
    }

    /** Cuántos eventos hubo de cada tipo, de más a menos (empates en el orden del enum). */
    fun count(types: List<SoundType?>): List<Pair<SoundType, Int>> =
        types.filterNotNull().groupingBy { it }.eachCount().toList()
            .sortedWith(compareByDescending<Pair<SoundType, Int>> { it.second }.thenBy { it.first.ordinal })
}
