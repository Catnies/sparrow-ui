package net.momirealms.sparrow.ui.network.packet;

public final class PacketTypes {
    private PacketTypes() {
    }

    public static final class Handshaking {
        private Handshaking() {
        }

        public static final class Serverbound {
            public static final PacketType INTENTION = new PacketType("minecraft:intention", ConnectionState.HANDSHAKING, PacketFlow.SERVERBOUND);

            private Serverbound() {
            }
        }
    }

    public static final class Status {
        private Status() {
        }

        public static final class Serverbound {
            public static final PacketType PING_REQUEST = new PacketType("minecraft:ping_request", ConnectionState.STATUS, PacketFlow.SERVERBOUND);
            public static final PacketType STATUS_REQUEST = new PacketType("minecraft:status_request", ConnectionState.STATUS, PacketFlow.SERVERBOUND);

            private Serverbound() {
            }
        }

        public static final class Clientbound {
            public static final PacketType PONG_RESPONSE = new PacketType("minecraft:pong_response", ConnectionState.STATUS, PacketFlow.CLIENTBOUND);
            public static final PacketType STATUS_RESPONSE = new PacketType("minecraft:status_response", ConnectionState.STATUS, PacketFlow.CLIENTBOUND);

            private Clientbound() {
            }
        }
    }

    public static final class Login {
        private Login() {
        }

        public static final class Serverbound {
            public static final PacketType COOKIE_RESPONSE = new PacketType("minecraft:cookie_response", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);
            public static final PacketType CUSTOM_QUERY_ANSWER = new PacketType("minecraft:custom_query_answer", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);
            public static final PacketType HELLO = new PacketType("minecraft:hello", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);
            public static final PacketType KEY = new PacketType("minecraft:key", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);
            public static final PacketType LOGIN_ACKNOWLEDGED = new PacketType("minecraft:login_acknowledged", ConnectionState.LOGIN, PacketFlow.SERVERBOUND);

            private Serverbound() {
            }
        }

        public static final class Clientbound {
            public static final PacketType COOKIE_REQUEST = new PacketType("minecraft:cookie_request", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND);
            public static final PacketType CUSTOM_QUERY = new PacketType("minecraft:custom_query", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND);
            public static final PacketType HELLO = new PacketType("minecraft:hello", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND);
            public static final PacketType LOGIN_COMPRESSION = new PacketType("minecraft:login_compression", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND);
            public static final PacketType LOGIN_DISCONNECT = new PacketType("minecraft:login_disconnect", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND);
            public static final PacketType LOGIN_FINISHED = new PacketType("minecraft:login_finished", ConnectionState.LOGIN, PacketFlow.CLIENTBOUND);

            private Clientbound() {
            }
        }
    }

    public static final class Play {
        private Play() {
        }

        public static final class Serverbound {
            public static final PacketType ACCEPT_TELEPORTATION = new PacketType("minecraft:accept_teleportation", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType ATTACK = new PacketType("minecraft:attack", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType BLOCK_ENTITY_TAG_QUERY = new PacketType("minecraft:block_entity_tag_query", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType BUNDLE_ITEM_SELECTED = new PacketType("minecraft:bundle_item_selected", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHANGE_DIFFICULTY = new PacketType("minecraft:change_difficulty", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHANGE_GAME_MODE = new PacketType("minecraft:change_game_mode", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHAT = new PacketType("minecraft:chat", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHAT_ACK = new PacketType("minecraft:chat_ack", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHAT_COMMAND = new PacketType("minecraft:chat_command", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHAT_COMMAND_SIGNED = new PacketType("minecraft:chat_command_signed", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHAT_SESSION_UPDATE = new PacketType("minecraft:chat_session_update", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CHUNK_BATCH_RECEIVED = new PacketType("minecraft:chunk_batch_received", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CLIENT_COMMAND = new PacketType("minecraft:client_command", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CLIENT_INFORMATION = new PacketType("minecraft:client_information", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CLIENT_TICK_END = new PacketType("minecraft:client_tick_end", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType COMMAND_SUGGESTION = new PacketType("minecraft:command_suggestion", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CONFIGURATION_ACKNOWLEDGED = new PacketType("minecraft:configuration_acknowledged", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CONTAINER_BUTTON_CLICK = new PacketType("minecraft:container_button_click", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CONTAINER_CLICK = new PacketType("minecraft:container_click", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CONTAINER_CLOSE = new PacketType("minecraft:container_close", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CONTAINER_SLOT_STATE_CHANGED = new PacketType("minecraft:container_slot_state_changed", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType COOKIE_RESPONSE = new PacketType("minecraft:cookie_response", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CUSTOM_CLICK_ACTION = new PacketType("minecraft:custom_click_action", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType CUSTOM_PAYLOAD = new PacketType("minecraft:custom_payload", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType DEBUG_SAMPLE_SUBSCRIPTION = new PacketType("minecraft:debug_sample_subscription", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType DEBUG_SUBSCRIPTION_REQUEST = new PacketType("minecraft:debug_subscription_request", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType EDIT_BOOK = new PacketType("minecraft:edit_book", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType ENTITY_TAG_QUERY = new PacketType("minecraft:entity_tag_query", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType INTERACT = new PacketType("minecraft:interact", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType JIGSAW_GENERATE = new PacketType("minecraft:jigsaw_generate", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType KEEP_ALIVE = new PacketType("minecraft:keep_alive", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType LOCK_DIFFICULTY = new PacketType("minecraft:lock_difficulty", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType MOVE_PLAYER_POS = new PacketType("minecraft:move_player_pos", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType MOVE_PLAYER_POS_ROT = new PacketType("minecraft:move_player_pos_rot", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType MOVE_PLAYER_ROT = new PacketType("minecraft:move_player_rot", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType MOVE_PLAYER_STATUS_ONLY = new PacketType("minecraft:move_player_status_only", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType MOVE_VEHICLE = new PacketType("minecraft:move_vehicle", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PADDLE_BOAT = new PacketType("minecraft:paddle_boat", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PICK_ITEM_FROM_BLOCK = new PacketType("minecraft:pick_item_from_block", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PICK_ITEM_FROM_ENTITY = new PacketType("minecraft:pick_item_from_entity", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PING_REQUEST = new PacketType("minecraft:ping_request", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PLACE_RECIPE = new PacketType("minecraft:place_recipe", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PLAYER_ABILITIES = new PacketType("minecraft:player_abilities", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PLAYER_ACTION = new PacketType("minecraft:player_action", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PLAYER_COMMAND = new PacketType("minecraft:player_command", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PLAYER_INPUT = new PacketType("minecraft:player_input", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PLAYER_LOADED = new PacketType("minecraft:player_loaded", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PONG = new PacketType("minecraft:pong", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType PUNCH = new PacketType("minecraft:punch", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType RECIPE_BOOK_CHANGE_SETTINGS = new PacketType("minecraft:recipe_book_change_settings", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType RECIPE_BOOK_SEEN_RECIPE = new PacketType("minecraft:recipe_book_seen_recipe", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType RENAME_ITEM = new PacketType("minecraft:rename_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType RESOURCE_PACK = new PacketType("minecraft:resource_pack", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SEEN_ADVANCEMENTS = new PacketType("minecraft:seen_advancements", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SELECT_TRADE = new PacketType("minecraft:select_trade", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_BEACON = new PacketType("minecraft:set_beacon", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_CARRIED_ITEM = new PacketType("minecraft:set_carried_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_COMMAND_BLOCK = new PacketType("minecraft:set_command_block", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_COMMAND_MINECART = new PacketType("minecraft:set_command_minecart", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_CREATIVE_MODE_SLOT = new PacketType("minecraft:set_creative_mode_slot", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_GAME_RULE = new PacketType("minecraft:set_game_rule", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_JIGSAW_BLOCK = new PacketType("minecraft:set_jigsaw_block", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_STRUCTURE_BLOCK = new PacketType("minecraft:set_structure_block", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SET_TEST_BLOCK = new PacketType("minecraft:set_test_block", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SIGN_UPDATE = new PacketType("minecraft:sign_update", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SPECTATE_ENTITY = new PacketType("minecraft:spectate_entity", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SPECTATOR_ACTION = new PacketType("minecraft:spectator_action", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType SWING = new PacketType("minecraft:swing", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType TELEPORT_TO_ENTITY = new PacketType("minecraft:teleport_to_entity", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType TEST_INSTANCE_BLOCK_ACTION = new PacketType("minecraft:test_instance_block_action", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType USE_ITEM = new PacketType("minecraft:use_item", ConnectionState.PLAY, PacketFlow.SERVERBOUND);
            public static final PacketType USE_ITEM_ON = new PacketType("minecraft:use_item_on", ConnectionState.PLAY, PacketFlow.SERVERBOUND);

            private Serverbound() {
            }
        }

        public static final class Clientbound {
            public static final PacketType ADD_ENTITY = new PacketType("minecraft:add_entity", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType ADD_EXPERIENCE_ORB = new PacketType("minecraft:add_experience_orb", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType ADD_TRANSIENT_BLOCK = new PacketType("minecraft:add_transient_block", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType ANIMATE = new PacketType("minecraft:animate", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType AWARD_STATS = new PacketType("minecraft:award_stats", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BLOCK_CHANGED_ACK = new PacketType("minecraft:block_changed_ack", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BLOCK_DESTRUCTION = new PacketType("minecraft:block_destruction", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BLOCK_ENTITY_DATA = new PacketType("minecraft:block_entity_data", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BLOCK_EVENT = new PacketType("minecraft:block_event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BLOCK_UPDATE = new PacketType("minecraft:block_update", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BOSS_EVENT = new PacketType("minecraft:boss_event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType BUNDLE_DELIMITER = new PacketType("minecraft:bundle_delimiter", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CHANGE_DIFFICULTY = new PacketType("minecraft:change_difficulty", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CHUNK_BATCH_FINISHED = new PacketType("minecraft:chunk_batch_finished", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CHUNK_BATCH_START = new PacketType("minecraft:chunk_batch_start", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CHUNKS_BIOMES = new PacketType("minecraft:chunks_biomes", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CLEAR_DIALOG = new PacketType("minecraft:clear_dialog", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CLEAR_TITLES = new PacketType("minecraft:clear_titles", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType COMMAND_SUGGESTIONS = new PacketType("minecraft:command_suggestions", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType COMMANDS = new PacketType("minecraft:commands", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CONTAINER_CLOSE = new PacketType("minecraft:container_close", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CONTAINER_SET_CONTENT = new PacketType("minecraft:container_set_content", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CONTAINER_SET_DATA = new PacketType("minecraft:container_set_data", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CONTAINER_SET_SLOT = new PacketType("minecraft:container_set_slot", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType COOKIE_REQUEST = new PacketType("minecraft:cookie_request", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType COOLDOWN = new PacketType("minecraft:cooldown", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CUSTOM_CHAT_COMPLETIONS = new PacketType("minecraft:custom_chat_completions", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CUSTOM_PAYLOAD = new PacketType("minecraft:custom_payload", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType CUSTOM_REPORT_DETAILS = new PacketType("minecraft:custom_report_details", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DAMAGE_EVENT = new PacketType("minecraft:damage_event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DEBUG_BLOCK_VALUE = new PacketType("minecraft:debug/block_value", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DEBUG_CHUNK_VALUE = new PacketType("minecraft:debug/chunk_value", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DEBUG_ENTITY_VALUE = new PacketType("minecraft:debug/entity_value", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DEBUG_EVENT = new PacketType("minecraft:debug/event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DEBUG_SAMPLE = new PacketType("minecraft:debug_sample", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DELETE_CHAT = new PacketType("minecraft:delete_chat", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DISCONNECT = new PacketType("minecraft:disconnect", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType DISGUISED_CHAT = new PacketType("minecraft:disguised_chat", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType ENTITY_EVENT = new PacketType("minecraft:entity_event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType ENTITY_POSITION_SYNC = new PacketType("minecraft:entity_position_sync", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType EXPLODE = new PacketType("minecraft:explode", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType FORGET_LEVEL_CHUNK = new PacketType("minecraft:forget_level_chunk", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType GAME_EVENT = new PacketType("minecraft:game_event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType GAME_RULE_VALUES = new PacketType("minecraft:game_rule_values", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType GAME_TEST_HIGHLIGHT_POS = new PacketType("minecraft:game_test_highlight_pos", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType HORSE_SCREEN_OPEN = new PacketType("minecraft:horse_screen_open", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType HURT_ANIMATION = new PacketType("minecraft:hurt_animation", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType INITIALIZE_BORDER = new PacketType("minecraft:initialize_border", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType KEEP_ALIVE = new PacketType("minecraft:keep_alive", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType LEVEL_CHUNK_WITH_LIGHT = new PacketType("minecraft:level_chunk_with_light", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType LEVEL_EVENT = new PacketType("minecraft:level_event", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType LEVEL_PARTICLES = new PacketType("minecraft:level_particles", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType LIGHT_UPDATE = new PacketType("minecraft:light_update", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType LOGIN = new PacketType("minecraft:login", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType LOW_DISK_SPACE_WARNING = new PacketType("minecraft:low_disk_space_warning", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MAP_ITEM_DATA = new PacketType("minecraft:map_item_data", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MERCHANT_OFFERS = new PacketType("minecraft:merchant_offers", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MOUNT_SCREEN_OPEN = new PacketType("minecraft:mount_screen_open", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MOVE_ENTITY_POS = new PacketType("minecraft:move_entity_pos", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MOVE_ENTITY_POS_ROT = new PacketType("minecraft:move_entity_pos_rot", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MOVE_ENTITY_ROT = new PacketType("minecraft:move_entity_rot", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MOVE_MINECART_ALONG_TRACK = new PacketType("minecraft:move_minecart_along_track", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType MOVE_VEHICLE = new PacketType("minecraft:move_vehicle", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType OPEN_BOOK = new PacketType("minecraft:open_book", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType OPEN_SCREEN = new PacketType("minecraft:open_screen", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType OPEN_SIGN_EDITOR = new PacketType("minecraft:open_sign_editor", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PING = new PacketType("minecraft:ping", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLACE_GHOST_RECIPE = new PacketType("minecraft:place_ghost_recipe", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_ABILITIES = new PacketType("minecraft:player_abilities", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_CHAT = new PacketType("minecraft:player_chat", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_COMBAT_END = new PacketType("minecraft:player_combat_end", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_COMBAT_ENTER = new PacketType("minecraft:player_combat_enter", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_COMBAT_KILL = new PacketType("minecraft:player_combat_kill", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_INFO_REMOVE = new PacketType("minecraft:player_info_remove", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_INFO_UPDATE = new PacketType("minecraft:player_info_update", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_LOOK_AT = new PacketType("minecraft:player_look_at", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_POSITION = new PacketType("minecraft:player_position", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PLAYER_ROTATION = new PacketType("minecraft:player_rotation", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PONG_RESPONSE = new PacketType("minecraft:pong_response", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType POST_EFFECTS = new PacketType("minecraft:post_effects", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType PROJECTILE_POWER = new PacketType("minecraft:projectile_power", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RECIPE_BOOK_ADD = new PacketType("minecraft:recipe_book_add", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RECIPE_BOOK_REMOVE = new PacketType("minecraft:recipe_book_remove", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RECIPE_BOOK_SETTINGS = new PacketType("minecraft:recipe_book_settings", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType REMOVE_ENTITIES = new PacketType("minecraft:remove_entities", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType REMOVE_MOB_EFFECT = new PacketType("minecraft:remove_mob_effect", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RESET_SCORE = new PacketType("minecraft:reset_score", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RESOURCE_PACK_POP = new PacketType("minecraft:resource_pack_pop", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RESOURCE_PACK_PUSH = new PacketType("minecraft:resource_pack_push", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType RESPAWN = new PacketType("minecraft:respawn", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType ROTATE_HEAD = new PacketType("minecraft:rotate_head", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SECTION_BLOCKS_UPDATE = new PacketType("minecraft:section_blocks_update", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SELECT_ADVANCEMENTS_TAB = new PacketType("minecraft:select_advancements_tab", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SERVER_DATA = new PacketType("minecraft:server_data", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SERVER_LINKS = new PacketType("minecraft:server_links", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_ACTION_BAR_TEXT = new PacketType("minecraft:set_action_bar_text", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_BORDER_CENTER = new PacketType("minecraft:set_border_center", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_BORDER_LERP_SIZE = new PacketType("minecraft:set_border_lerp_size", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_BORDER_SIZE = new PacketType("minecraft:set_border_size", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_BORDER_WARNING_DELAY = new PacketType("minecraft:set_border_warning_delay", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_BORDER_WARNING_DISTANCE = new PacketType("minecraft:set_border_warning_distance", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_CAMERA = new PacketType("minecraft:set_camera", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_CHUNK_CACHE_CENTER = new PacketType("minecraft:set_chunk_cache_center", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_CHUNK_CACHE_RADIUS = new PacketType("minecraft:set_chunk_cache_radius", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_CURSOR_ITEM = new PacketType("minecraft:set_cursor_item", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_DEFAULT_SPAWN_POSITION = new PacketType("minecraft:set_default_spawn_position", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_DISPLAY_OBJECTIVE = new PacketType("minecraft:set_display_objective", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_ENTITY_DATA = new PacketType("minecraft:set_entity_data", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_ENTITY_LINK = new PacketType("minecraft:set_entity_link", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_ENTITY_MOTION = new PacketType("minecraft:set_entity_motion", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_EQUIPMENT = new PacketType("minecraft:set_equipment", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_EXPERIENCE = new PacketType("minecraft:set_experience", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_HEALTH = new PacketType("minecraft:set_health", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_HELD_SLOT = new PacketType("minecraft:set_held_slot", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_OBJECTIVE = new PacketType("minecraft:set_objective", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_PASSENGERS = new PacketType("minecraft:set_passengers", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_PLAYER_INVENTORY = new PacketType("minecraft:set_player_inventory", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_PLAYER_TEAM = new PacketType("minecraft:set_player_team", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_SCORE = new PacketType("minecraft:set_score", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_SIMULATION_DISTANCE = new PacketType("minecraft:set_simulation_distance", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_SUBTITLE_TEXT = new PacketType("minecraft:set_subtitle_text", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_TIME = new PacketType("minecraft:set_time", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_TITLE_TEXT = new PacketType("minecraft:set_title_text", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SET_TITLES_ANIMATION = new PacketType("minecraft:set_titles_animation", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SHOW_DIALOG = new PacketType("minecraft:show_dialog", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SOUND = new PacketType("minecraft:sound", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SOUND_ENTITY = new PacketType("minecraft:sound_entity", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType START_CONFIGURATION = new PacketType("minecraft:start_configuration", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType STOP_SOUND = new PacketType("minecraft:stop_sound", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType STORE_COOKIE = new PacketType("minecraft:store_cookie", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SWING_ANIMATION = new PacketType("minecraft:swing_animation", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType SYSTEM_CHAT = new PacketType("minecraft:system_chat", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TAB_LIST = new PacketType("minecraft:tab_list", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TAG_QUERY = new PacketType("minecraft:tag_query", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TAKE_ITEM_ENTITY = new PacketType("minecraft:take_item_entity", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TELEPORT_ENTITY = new PacketType("minecraft:teleport_entity", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TEST_INSTANCE_BLOCK_STATUS = new PacketType("minecraft:test_instance_block_status", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TICKING_STATE = new PacketType("minecraft:ticking_state", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TICKING_STEP = new PacketType("minecraft:ticking_step", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType TRANSFER = new PacketType("minecraft:transfer", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_ADVANCEMENTS = new PacketType("minecraft:update_advancements", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_ATTRIBUTES = new PacketType("minecraft:update_attributes", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_MOB_EFFECT = new PacketType("minecraft:update_mob_effect", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_RECIPES = new PacketType("minecraft:update_recipes", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_TAGS = new PacketType("minecraft:update_tags", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);
            public static final PacketType WAYPOINT = new PacketType("minecraft:waypoint", ConnectionState.PLAY, PacketFlow.CLIENTBOUND);

            private Clientbound() {
            }
        }
    }

    public static final class Configuration {
        private Configuration() {
        }

        public static final class Serverbound {
            public static final PacketType ACCEPT_CODE_OF_CONDUCT = new PacketType("minecraft:accept_code_of_conduct", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType CLIENT_INFORMATION = new PacketType("minecraft:client_information", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType COOKIE_RESPONSE = new PacketType("minecraft:cookie_response", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType CUSTOM_CLICK_ACTION = new PacketType("minecraft:custom_click_action", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType CUSTOM_PAYLOAD = new PacketType("minecraft:custom_payload", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType FINISH_CONFIGURATION = new PacketType("minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType KEEP_ALIVE = new PacketType("minecraft:keep_alive", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType PONG = new PacketType("minecraft:pong", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType RESOURCE_PACK = new PacketType("minecraft:resource_pack", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);
            public static final PacketType SELECT_KNOWN_PACKS = new PacketType("minecraft:select_known_packs", ConnectionState.CONFIGURATION, PacketFlow.SERVERBOUND);

            private Serverbound() {
            }
        }

        public static final class Clientbound {
            public static final PacketType CLEAR_DIALOG = new PacketType("minecraft:clear_dialog", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType CODE_OF_CONDUCT = new PacketType("minecraft:code_of_conduct", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType COOKIE_REQUEST = new PacketType("minecraft:cookie_request", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType CUSTOM_PAYLOAD = new PacketType("minecraft:custom_payload", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType CUSTOM_REPORT_DETAILS = new PacketType("minecraft:custom_report_details", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType DISCONNECT = new PacketType("minecraft:disconnect", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType FINISH_CONFIGURATION = new PacketType("minecraft:finish_configuration", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType KEEP_ALIVE = new PacketType("minecraft:keep_alive", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType PING = new PacketType("minecraft:ping", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType POST_EFFECTS = new PacketType("minecraft:post_effects", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType REGISTRY_DATA = new PacketType("minecraft:registry_data", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType RESET_CHAT = new PacketType("minecraft:reset_chat", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType RESOURCE_PACK_POP = new PacketType("minecraft:resource_pack_pop", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType RESOURCE_PACK_PUSH = new PacketType("minecraft:resource_pack_push", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType SELECT_KNOWN_PACKS = new PacketType("minecraft:select_known_packs", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType SERVER_LINKS = new PacketType("minecraft:server_links", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType SHOW_DIALOG = new PacketType("minecraft:show_dialog", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType STORE_COOKIE = new PacketType("minecraft:store_cookie", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType TRANSFER = new PacketType("minecraft:transfer", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_ENABLED_FEATURES = new PacketType("minecraft:update_enabled_features", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);
            public static final PacketType UPDATE_TAGS = new PacketType("minecraft:update_tags", ConnectionState.CONFIGURATION, PacketFlow.CLIENTBOUND);

            private Clientbound() {
            }
        }
    }
}
