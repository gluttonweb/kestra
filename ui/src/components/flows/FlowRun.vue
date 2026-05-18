<template>
    <template v-if="flow">
        <KsAlert v-if="flow.disabled" type="warning" showIcon :closable="false">
            <strong>{{ t('disabled flow title') }}</strong><br>
            {{ t('disabled flow desc') }}
        </KsAlert>
        <div class="flow-execution-checks-alerts">
            <KsAlert v-for="(alert, idx) in checks || []" :type="alert.style.toLowerCase()" showIcon :closable="false" :key="idx">
                {{ alert.message }}
            </KsAlert>
        </div>
        <KsForm labelPosition="top" :model="inputs" ref="form" @submit.prevent="false">
            <InputsForm
                ref="inputsFormRef"
                :initialInputs="flow.inputs"
                :selectedTrigger="selectedTrigger"
                :flow="flow"
                v-model="inputs"
                :executeClicked="executeClicked"
                @confirm="onSubmit(form)"
                @update:model-value-no-default="values => inputsNoDefaults=(values as Record<string, unknown>)"
                @update:checks="values => checks=(values as Array<Record<string, any>>)"
            />

            <KsCollapse v-model="collapseName">
                <KsCollapseItem :title="t('advanced configuration')" name="advanced">
                    <KsFormItem
                        :label="t('execution labels')"
                    >
                        <LabelInput
                            v-model:labels="executionLabels"
                        />
                    </KsFormItem>
                    <KsFormItem
                        :label="t('scheduleDate')"
                    >
                        <KsDatePicker
                            v-model="scheduleDate"
                            type="datetime"
                        />
                    </KsFormItem>
                </KsCollapseItem>
                <KsCollapseItem :title="t('curl.command')" name="curl">
                    <Curl :flow="flow" :executionLabels="executionLabels" :inputs="inputs" />
                </KsCollapseItem>
                <KsCollapseItem v-if="hasWebhookTriggers" :title="t('webhook.curl_command')" name="webhook-curl">
                    <WebhookCurl :flow="flow" />
                </KsCollapseItem>
            </KsCollapse>

            <div class="bottom-buttons" v-if="!props.embed">
                <div class="left-align">
                    <KsFormItem>
                        <KsButton v-if="execution && (execution.inputs || hasExecutionLabels())" :icon="ContentCopy" @click="fillInputsFromExecution">
                            {{ t('prefill inputs') }}
                        </KsButton>
                    </KsFormItem>
                </div>
                <div class="right-align">
                    <KsFormItem class="submit">
                        <span data-onboarding-target="flow-execute-confirm-button">
                            <KsButton
                                :icon="buttonIconProp"
                                :disabled="!flowCanBeExecuted || hasBlockingChecks()"
                                class="flow-run-trigger-button"
                                type="primary"
                                nativeType="submit"
                                @click.prevent="onSubmit(form); executeClicked = true;"
                            >
                                {{ t(buttonTextProp) }}
                            </KsButton>
                        </span>
                        <KsText v-if="haveBadLabels" type="danger" size="small">
                            {{ t('wrong labels') }}
                        </KsText>
                    </KsFormItem>
                </div>
            </div>
        </KsForm>
    </template>
</template>

<script setup lang="ts">
    import {ref, computed, watch, useTemplateRef} from "vue"
    import {useRoute} from "vue-router"
    import {useI18n} from "vue-i18n"
    import {useRouter} from "vue-router"
    import moment from "moment-timezone"
    import ContentCopy from "vue-material-design-icons/ContentCopy.vue"
    import Play from "vue-material-design-icons/Play.vue"
    import {useCoreStore} from "../../stores/core"
    import {useApiStore} from "../../stores/api"
    import {useMiscStore} from "override/stores/misc"
    import {useExecutionsStore} from "../../stores/executions"
    // @ts-ignore - no type declarations for JS utility
    import {executeTask} from "../../utils/submitTask"
    import {executeFlowBehaviours, storageKeys} from "../../utils/constants"
    import {normalize} from "../../utils/inputs"
    import Curl from "./Curl.vue"
    import WebhookCurl from "./WebhookCurl.vue"
    import InputsForm from "../../components/inputs/InputsForm.vue"
    import LabelInput from "../../components/labels/LabelInput.vue"
    import {useToast} from "../../utils/toast"

    const props = defineProps<{
        redirect?: boolean
        embed?: boolean
        replaySubmit?: ((args: Record<string, unknown>) => void) | null
        selectedTrigger?: Record<string, any>
        buttonText?: string
        buttonIcon?: object | ((...args: unknown[]) => unknown)
        buttonTestId?: string
    }>()

    const emit = defineEmits<{
        executionTrigger: []
        updateInputs: [Record<string, unknown>]
        updateLabels: [Array<{ key: string; value: string }>]
    }>()

    const {t} = useI18n({useScope: "global"})
    const route = useRoute()
    const router = useRouter()
    const toast = useToast()
    const coreStore = useCoreStore()
    const apiStore = useApiStore()
    const miscStore = useMiscStore()
    const executionsStore = useExecutionsStore()

    const form = useTemplateRef<{ validate: (cb: (valid: boolean) => void) => void }>("form")
    const inputsFormRef = useTemplateRef<{ inputsValues: Record<string, unknown>; inputsMetaData: Array<{ id: string; isDefault: boolean }> }>("inputsFormRef")

    const inputs = ref<Record<string, unknown>>({})
    const inputsNoDefaults = ref<Record<string, unknown>>({})
    const executionLabels = ref<Array<{ key: string; value: string }>>([])
    const scheduleDate = ref<string | undefined>(undefined)
    const collapseName = ref<string | undefined>(undefined)
    const newTab = ref(localStorage.getItem(storageKeys.EXECUTE_FLOW_BEHAVIOUR) === executeFlowBehaviours.NEW_TAB)
    const executeClicked = ref(false)
    const checks = ref<Array<Record<string, any>>>([])

    const buttonTextProp = computed(() => props.buttonText ?? "launch execution")
    const buttonIconProp = computed(() => props.buttonIcon ?? Play)
    const redirectProp = computed(() => props.redirect ?? true)

    const flow = computed(() => executionsStore.flow)
    const execution = computed(() => executionsStore.execution)

    const haveBadLabels = computed(() =>
        executionLabels.value.some(label => (label.key && !label.value) || (!label.key && label.value)),
    )

    const flowCanBeExecuted = computed(() =>
        flow.value && !flow.value.disabled && !haveBadLabels.value,
    )

    const hasWebhookTriggers = computed(() => {
        if (!flow.value?.triggers) {
            return false
        }
        return flow.value.triggers.some((trigger: Record<string, any>) =>
            trigger.type === "io.kestra.plugin.core.trigger.Webhook" &&
            (trigger.disabled === undefined || trigger.disabled === false),
        )
    })

    watch(inputs, () => {
        emit("updateInputs", inputs.value)
    }, {deep: true})

    watch(executionLabels, () => {
        emit("updateLabels", executionLabels.value)
    }, {deep: true})

    function hasBlockingChecks() {
        return checks.value.filter(check => check.behavior === "BLOCK_EXECUTION").length > 0
    }

    function getExecutionLabels() {
        if (!execution.value?.labels) {
            return []
        }
        if (!flow.value?.labels) {
            return execution.value.labels
        }
        return execution.value.labels.filter((label: { key: string; value: string }) => {
            return !flow.value!.labels.some((flowLabel: { key: string; value: string }) =>
                flowLabel.key === label.key && flowLabel.value === label.value,
            )
        })
    }

    function hasExecutionLabels() {
        return getExecutionLabels().length > 0
    }

    function fillInputsFromExecution() {
        const toIgnore = miscStore.configs?.hiddenLabelsPrefixes || []
        executionLabels.value = getExecutionLabels().filter((item: { key: string }) =>
            !toIgnore.some((prefix: string) => item.key.startsWith(prefix)),
        )

        const inputsFormEl = inputsFormRef.value
        if (!inputsFormEl || !flow.value?.inputs) {
            return
        }

        const nonEmptyInputNames = Object.keys(execution.value?.inputs ?? {})
        flow.value.inputs
            .filter((input: { id: string }) => nonEmptyInputNames.includes(input.id))
            .forEach((input: { id: string; type: string }) => {
                let value = execution.value!.inputs?.[input.id]
                inputsFormEl.inputsValues[input.id] = normalize(input.type as any, value)
                const meta = inputsFormEl.inputsMetaData.find((m: { id: string }) => m.id === input.id)
                if (meta) {
                    meta.isDefault = false
                }
            })
    }

    function onSubmit(formRef: { validate: (cb: (valid: boolean) => void) => void } | null | undefined) {
        if (formRef && flowCanBeExecuted.value) {
            apiStore.posthogEvents({
                type: "FLOW_EXECUTION",
                action: "submit",
            })
            checks.value = []
            executeClicked.value = false
            coreStore.message = undefined
            formRef.validate((valid: boolean) => {
                if (!valid) {
                    return false
                }

                if (props.replaySubmit) {
                    props.replaySubmit({
                        formRef,
                        id: flow.value!.id,
                        namespace: flow.value!.namespace,
                        inputs: props.selectedTrigger?.inputs
                            ? {...props.selectedTrigger.inputs, ...inputsNoDefaults.value}
                            : inputsNoDefaults.value,
                        labels: [...new Set(
                            executionLabels.value
                                .filter(label => label.key && label.value)
                                .map(label => `${label.key}:${label.value}`),
                        ), "system.from:ui"],
                        scheduleDate: scheduleDate.value,
                    })
                } else {
                    const shouldShowOnboardingSuccessAnimation = route.query.onboardingPreset === "true"

                    const submitor = {
                        $moment: moment,
                        $router: router,
                        $route: route,
                        $toast: () => toast,
                        $t: t,
                    }

                    executeTask(submitor, flow.value!, props.selectedTrigger?.inputs
                                    ? {...props.selectedTrigger.inputs, ...inputsNoDefaults.value}
                                    : inputsNoDefaults.value,
                                {
                                    redirect: redirectProp.value,
                                    newTab: newTab.value,
                                    id: flow.value!.id,
                                    namespace: flow.value!.namespace,
                                    labels: [...new Set(
                                        executionLabels.value
                                            .filter(label => label.key && label.value)
                                            .map(label => `${label.key}:${label.value}`),
                                    ), "system.from:ui"],
                                    scheduleDate: moment(scheduleDate.value).tz(localStorage.getItem(storageKeys.TIMEZONE_STORAGE_KEY) ?? moment.tz.guess()).toISOString(true),
                                    nextStep: true,
                                    query: shouldShowOnboardingSuccessAnimation ? {
                                        autoExpandGantt: "true",
                                        onboardingSuccess: "true",
                                    } : undefined,
                                })
                }
                executeClicked.value = true
                emit("executionTrigger")
            })
        }
    }
</script>

<style scoped lang="scss">
    .flow-execution-checks-alerts {
        margin-bottom: 1rem;
    }
    :deep(.kel-collapse) {
        border-radius: var(--kel-border-radius-round);
        border: 1px solid var(--ks-border-primary);
        background: var(--ks-tag-background);

        .kel-collapse-item__header {
            background: transparent;
            border-bottom: 1px solid var(--ks-border-primary);
            font-size: var(--ks-font-size-sm);
        }

        .kel-collapse-item__content {
            background: var(--ks-tag-background);
            border-bottom: 1px solid var(--ks-border-primary);
        }

        .kel-collapse-item__header, .kel-collapse-item__content {
            &:last-child {
                border-bottom-left-radius: var(--kel-border-radius-round);
                border-bottom-right-radius: var(--kel-border-radius-round);
            }
        }
    }

    .onboarding-glow {
        animation: glowAnimation 1s infinite alternate;
    }

    @keyframes glowAnimation {
        0% {
            box-shadow: 0px 0px 0px 0px #8405FF;
        }
        100% {
            box-shadow: 0px 0px 50px 2px #8405FF;
        }
    }
</style>
