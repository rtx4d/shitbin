package dog.del.app.frontend

import dog.del.app.config.AppConfig
import dog.del.app.dto.ApiCredentialDto
import dog.del.app.dto.FrontendDocumentDto
import dog.del.app.dto.NewApiCredentialDto
import dog.del.app.dto.UserDto
import dog.del.app.session.*
import dog.del.app.stats.StatisticsReporter
import dog.del.app.utils.locale
import dog.del.app.utils.respondMessage
import dog.del.commons.keygen.RandomKeyGenerator
import dog.del.data.base.Database
import dog.del.data.base.model.api.XdApiCredential
import dog.del.data.base.model.document.XdDocument
import dog.del.data.base.model.user.XdUser
import dog.del.data.base.model.user.XdUserRole
import dog.del.data.base.suspended
import dog.del.data.base.transactionalAsync
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.pebble.respondTemplate
import io.ktor.request.receiveParameters
import io.ktor.response.respondRedirect
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.util.getOrFail
import jetbrains.exodus.database.TransientEntityStore
import kotlinx.coroutines.*
import kotlinx.dnq.query.filter
import kotlinx.dnq.query.sortedBy
import kotlinx.dnq.query.toList
import kotlinx.dnq.util.findById
import me.gosimple.nbvcxz.Nbvcxz
import org.koin.ktor.ext.inject

fun Route.user() = route("/") {
    val store by inject<TransientEntityStore>()
    val reporter by inject<StatisticsReporter>()
    val appConfig by inject<AppConfig>()
    val estimator by inject<Nbvcxz>()

    route("/login") {
        post {
            if (call.session() != null) {
                call.respondRedirect("/me")
                return@post
            }
            val params = call.receiveParameters()
            val username = params.getOrFail("username")
            val password = params.getOrFail("password")
            val success = store.suspended {
                val usr = XdUser.find(username)
                if (usr != null) {
                    if (usr.checkPassword(password)) {
                        call.setWebSession(WebSession(usr.xdId))
                        return@suspended true
                    }
                }
                false
            }
            if (success) {
                call.respondRedirect("/me")
            } else {
                call.respondMessage("Sign in failed", "Failed to sign in", code = HttpStatusCode.Unauthorized)
            }
        }
        get {
            if (call.session() != null) {
                val existingUser = call.user(store)
                val isAnon = store.suspended(readonly = true) {
                    existingUser.role == XdUserRole.ANON
                }
                if (isAnon) {
                    // Destroy existing session
                    call.clearWebSession()
                } else {
                    // User is already logged in
                    call.respondRedirect("/me")
                    return@get
                }
            }
            call.respondTemplate(
                "user/login", mapOf(
                    "title" to "Вход на говнокод",
                    "description" to "Вход на говнокод",
                    "formTitle" to "Вход",
                    "secondaryLink" to "/register",
                    "secondaryTitle" to "Регистрация"
                )
            )
        }
    }

    route("/logout") {
        get {
            call.clearWebSession()
            call.respondRedirect("/", false)
        }
    }

    route("/register") {
        post {
            // This will either get the existing (anonymous) user or create a new anon user and add it to the session
            val existingUser = call.user(store)
            val isAnon = store.suspended(readonly = true) {
                existingUser.role == XdUserRole.ANON
            }
            if (!isAnon) {
                call.respondRedirect("/me", false)
                return@post
            }
            val params = call.receiveParameters()
            val username = params.getOrFail("username")
            val password = params.getOrFail("password")
            // Todo: implement a proper pw policy using nbvcxz
            val result = estimator.estimate(password)
            if (!result.isMinimumEntropyMet) {
                call.respondMessage(
                    result.feedback?.warning ?: "Insecure password",
                    result.feedback?.suggestion?.joinToString("\n") { "- $it" } ?: "",
                    code = HttpStatusCode.NotAcceptable
                )
                return@post
            }
            val success = store.suspended {
                val usr = XdUser.find(username)
                if (usr == null) {
                    existingUser.signUp(username, password)
                    return@suspended true
                }
                false
            }
            if (success) {
                call.respondRedirect("/me", false)
                GlobalScope.launch {
                    reporter.reportEvent(StatisticsReporter.Event.USER_REGISTER, call.request)
                }
            } else {
                call.respondMessage("Username Taken", "This username is taken", code = HttpStatusCode.Conflict)
            }
        }
        get {
            call.respondTemplate(
                "user/login", mapOf(
                    "title" to "Регистрация на говнокод",
                    "description" to "Регистрация на говнокод",
                    "formTitle" to "Регистрация",
                    "secondaryLink" to "/login",
                    "secondaryTitle" to "Вход",
                    "check_pw" to true
                )
            )
        }
    }

    route("/me") {
        get {
            val usr = call.user(store)
            val isAnon = store.suspended(readonly = true) {
                usr.role == XdUserRole.ANON
            }
            if (isAnon) {
                call.respondRedirect("/login", false)
                return@get
            }
            val user = store.transactionalAsync { UserDto.fromUser(usr, call.locale) }
            val docs = store.suspended(readonly = true) {
                XdDocument.filter { it.owner eq usr }.sortedBy(XdDocument::created, asc = false).toList()
            }.map { FrontendDocumentDto().applyFrom(it, call) }

            call.respondTemplate(
                "user/user", mapOf(
                    "title" to "Профиль",
                    "description" to "Просмотр свеого профиля",
                    "user" to user.await(),
                    "pastes" to docs
                )
            )
        }

        route("changepass") {
            get {
                val usr = call.user(store)
                val requiresPassword = store.suspended(readonly = true) { usr.role.requiresPassword }
                if (!requiresPassword) {
                    call.respondRedirect("/login", false)
                    return@get
                }
                call.respondTemplate(
                    "user/changepass", mapOf(
                        "title" to "Изменение пароля",
                        "description" to "Изменение пароля"
                    )
                )
            }

            post {
                val usr = call.user(store)
                val requiresPassword = store.suspended(readonly = true) { usr.role.requiresPassword }
                if (!requiresPassword) {
                    call.respondRedirect("/", false)
                    return@post
                }
                val params = call.receiveParameters()
                val current = params.getOrFail("current")
                val new = params.getOrFail("password")
                val success = store.suspended {
                    if (usr.checkPassword(current)) {
                        usr.changePass(new)
                        return@suspended true
                    }
                    false
                }
                if (success) {
                    call.respondRedirect("/me")
                } else {
                    call.respondRedirect("/me/changepass")
                }
            }
        }
        // TODO: this is currently pretty messy, just like most of our frontend code. We NEED to clean this up.
        route("api") {
            get {
                val usr = call.user(store)
                val requiresPassword = store.suspended(readonly = true) { usr.role.requiresPassword }
                if (!requiresPassword) {
                    call.respondRedirect("/login", false)
                    return@get
                }
                call.respondTemplate(
                    "user/api", mapOf(
                        "title" to "Настройка API",
                        "description" to "Настройте свои параметры API",
                        "credentials" to store.suspended(readonly = true) {
                            XdApiCredential.findForUser(usr)
                                .map { ApiCredentialDto.fromApiCredential(it, call.locale) }
                        }
                    )
                )
            }

            route("new") {
                get {
                    val usr = call.user(store)
                    val requiresPassword = store.suspended(readonly = true) { usr.role.requiresPassword }
                    if (!requiresPassword) {
                        call.respondRedirect("/login", false)
                        return@get
                    }
                    call.respondTemplate(
                        "user/api_new", mapOf(
                            "title" to "Новый API ключ",
                            "description" to "Создание нового API ключа"
                        )
                    )
                }
                post {
                    val usr = call.user(store)
                    val requiresPassword = store.suspended(readonly = true) { usr.role.requiresPassword }
                    if (!requiresPassword) {
                        call.respondRedirect("/", false)
                        return@post
                    }
                    val params = call.receiveParameters()
                    val name = params.getOrFail("name")
                    val canCreateDocuments = params.get("canCreateDocuments") == "on"
                    val canUpdateDocuments = params.get("canUpdateDocuments") == "on"
                    val canDeleteDocuments = params.get("canDeleteDocuments") == "on"
                    val canListDocuments = params.get("canListDocuments") == "on"
                    val key = RandomKeyGenerator().createKey(appConfig.api.keyLength)

                    // Actually create the credentials in the db
                    store.suspended {
                        XdApiCredential.new(key, usr)?.apply {
                            this.name = name
                            this.canCreateDocuments = canCreateDocuments
                            this.canUpdateDocuments = canUpdateDocuments
                            this.canDeleteDocuments = canDeleteDocuments
                            this.canListDocuments = canListDocuments
                        }
                    }
                    call.respondTemplate(
                        "user/api_created", mapOf(
                            "title" to "Новый API ключ",
                            "description" to "Создание нового API ключа",
                            "cred" to NewApiCredentialDto(name, key)
                        )
                    )
                    GlobalScope.launch {
                        reporter.reportEvent(StatisticsReporter.Event.API_KEY_CREATE, call.request)
                    }
                }
            }

            route("delete/{id}") {
                get {
                    val usr = call.user(store)
                    val requiresPassword = store.suspended(readonly = true) { usr.role.requiresPassword }
                    if (!requiresPassword) {
                        call.respondRedirect("/", false)
                        return@get
                    }
                    val id = call.parameters["id"]!!
                    val success = store.suspended {
                        XdApiCredential.findById(id).apply {
                            if (usr == user) {
                                delete()
                                return@suspended true
                            }
                        }
                        false
                    }
                    if (success) {
                        call.respondRedirect("/me/api", false)
                        GlobalScope.launch {
                            reporter.reportEvent(StatisticsReporter.Event.API_KEY_DELETE, call.request)
                        }
                    } else {
                        call.respondRedirect("/", false)

                    }
                }
            }
        }
    }
}
