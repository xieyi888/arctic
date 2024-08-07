<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
/ -->

<script lang="ts">
import { computed, defineComponent, onBeforeUnmount, onMounted, reactive, ref, toRefs, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import UDetails from './components/Details.vue'
import errorMsg from './components/ErrorMsg.vue'
import type { DetailColumnItem } from '@/types/common.type'
import { upgradeStatusMap } from '@/types/common.type'
import { getHiveTableDetail, getUpgradeStatus } from '@/services/table.service'

export default defineComponent({
  name: 'Tables',
  components: {
    UDetails,
    ErrorMsg: errorMsg,
  },
  setup() {
    const upgradeStatus = upgradeStatusMap
    const statusInterval = ref<number>()
    const router = useRouter()
    const route = useRoute()
    const { t } = useI18n()

    const isSecondaryNav = computed(() => {
      return !!(route.path.includes('upgrade'))
    })

    const state = reactive({
      loading: false,
      showErrorMsg: false,
      activeKey: 'Details',
      status: '', // failed、upgrading、success、none
      displayStatus: '',
      errorMessage: '',
      tableName: 'tableName',
      partitionColumnList: [] as DetailColumnItem[],
      schema: [] as DetailColumnItem[],
    })

    const goBack = () => {
      router.back()
    }

    const params = computed(() => {
      return {
        ...route.query,
      }
    })

    const getTableUpgradeStatus = async (hideLoading = false) => {
      try {
        statusInterval.value && clearTimeout(statusInterval.value)
        const { catalog, db, table } = params.value
        if (!catalog || !db || !table) {
          return
        }
        !hideLoading && (state.loading = true)
        const result = await getUpgradeStatus({
          ...params.value,
        })
        const { status, errorMessage } = result
        state.status = status
        state.displayStatus = status === upgradeStatusMap.upgrading ? t('upgrading') : t('upgrade')
        state.errorMessage = errorMessage || ''
        if (status === upgradeStatusMap.upgrading) {
          statusInterval.value = setTimeout(() => {
            getTableUpgradeStatus(true)
          }, 1500)
        }
        else {
          if (status === upgradeStatusMap.none) {
            getHiveTableDetails()
          }
          else if (status === upgradeStatusMap.success) {
            router.replace({
              path: '/tables',
              query: {
                ...route.query,
              },
            })
          }
          else if (status === upgradeStatusMap.failed) {
            getHiveTableDetails()
          }
        }
      }
      finally {
        !hideLoading && (state.loading = false)
      }
    }

    async function getHiveTableDetails() {
      try {
        const { catalog, db, table } = params.value
        if (!catalog || !db || !table) {
          return
        }
        state.loading = true
        const result = await getHiveTableDetail({
          ...params.value,
        })
        const { partitionColumnList = [], schema, tableIdentifier } = result

        state.tableName = tableIdentifier?.tableName || ''

        state.partitionColumnList = partitionColumnList || []
        state.schema = schema || []
      }
      catch (error) {
      }
      finally {
        state.loading = false
      }
    }

    async function init() {
      await getTableUpgradeStatus()
    }

    function upgradeTable() {
      router.push({
        path: '/hive-tables/upgrade',
        query: {
          ...route.query,
        },
      })
    }
    function refresh() {
      init()
    }

    watch(
      () => route.query,
      (val, old) => {
        const { catalog, db, table } = val
        if (route.path === '/hive-tables' && (catalog !== old.catalog || db !== old.db || table !== old.table)) {
          init()
        }
      },
    )

    onBeforeUnmount(() => {
      clearTimeout(statusInterval.value)
    })

    onMounted(() => {
      init()
    })

    return {
      ...toRefs(state),
      isSecondaryNav,
      upgradeStatus,
      upgradeTable,
      goBack,
      refresh,
    }
  },
})
</script>

<template>
  <div class="hive-tables-wrap">
    <div v-if="!isSecondaryNav" class="tables-content">
      <div class="g-flex-jsb table-top">
        <span :title="tableName" class="table-name g-text-nowrap">{{ tableName }}</span>
        <div class="right-btn">
          <a-button type="primary" :disabled="status === upgradeStatus.upgrading" @click="upgradeTable">
            {{ displayStatus }}
          </a-button>
          <p v-if="status === upgradeStatus.failed" class="fail-msg" @click="showErrorMsg = true">
            {{ $t('lastUpgradingFailed') }}
          </p>
        </div>
      </div>
      <div class="content">
        <a-tabs v-model:activeKey="activeKey">
          <a-tab-pane key="Details" tab="Details">
            <UDetails :partition-column-list="partitionColumnList" :schema="schema" />
          </a-tab-pane>
        </a-tabs>
      </div>
    </div>
    <u-loading v-if="loading" />
    <!-- upgrade table secondary page -->
    <router-view v-if="isSecondaryNav" @go-back="goBack" @refresh="refresh" />
    <ErrorMsg v-if="showErrorMsg" :msg="errorMessage" @cancel="showErrorMsg = false" />
  </div>
</template>

<style lang="less" scoped>
.hive-tables-wrap {
  border: 1px solid #e8e8f0;
  padding: 12px 0;
  display: flex;
  height: 100%;
  flex: 1;
  flex-direction: column;
  .table-top {
    padding: 0 12px;
    .right-btn {
      position: relative;
    }
    .fail-msg {
      position: absolute;
      bottom: -30px;
      right: 0;
      z-index: 1;
      font-size: 12px;
      width: 90px;
      color: #ff4d4f;
      text-align: center;
      text-decoration-line: underline;
      cursor: pointer;
    }
  }
  .table-name {
    font-size: 24px;
    line-height: 1.5;
    margin-right: 16px;
    max-width: 400px;
    padding-left: 12px;
  }
  :deep(.ant-tabs-nav) {
    padding-left: 24px;
    margin-bottom: 0;
  }
}
</style>
