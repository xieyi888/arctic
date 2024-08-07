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
import { computed, defineComponent, onBeforeMount, reactive, toRefs } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CreateDBModal from './CreateDB.vue'
import useStore from '@/store/index'
import { getCatalogList, getDatabaseList, getTableList } from '@/services/table.service'
import type { ICatalogItem, ILableAndValue, IMap } from '@/types/common.type'
import { debounce } from '@/utils/index'
import { usePlaceholder } from '@/hooks/usePlaceholder'
import virtualRecycleScroller from '@/components/VirtualRecycleScroller.vue'

interface IDatabaseItem {
  id: string
  label: string
}
interface ITableItem {
  name: string
  type: string
}

export default defineComponent({
  name: 'TablesMenu',
  components: {
    CreateDBModal,
    VirtualRecycleScroller: virtualRecycleScroller,
  },
  emits: ['goCreatePage'],
  setup(_, { emit }) {
    const router = useRouter()
    const route = useRoute()
    const store = useStore()
    const state = reactive({
      catalogLoading: false as boolean,
      DBSearchInput: '' as string,
      tableSearchInput: '' as string,
      curCatalog: '',
      database: '',
      tableName: '',
      type: '',
      catalogOptions: [] as ILableAndValue[],
      showCreateDBModal: false,
      loading: false,
      tableLoading: false,
      databaseList: [] as IMap<string>[],
      tableList: [] as IMap<string>[],
      allDatabaseListLoaded: [] as IMap<string>[],
      allTableListLoaded: [] as IMap<string>[],
    })
    const storageTableKey = 'easylake-menu-catalog-db-table'
    const storageCataDBTable = JSON.parse(localStorage.getItem(storageTableKey) || '{}')

    const filteredDatabases = computed(() => {
      if (!state.allDatabaseListLoaded) {
        return []
      }
      return state.allDatabaseListLoaded.filter((ele) => {
        return ele.label.includes(state.DBSearchInput)
      })
    })

    const filteredTables = computed(() => {
      if (!state.allTableListLoaded) {
        return []
      }
      return state.allTableListLoaded.filter((ele) => {
        return ele.label.includes(state.tableSearchInput)
      })
    })

    const placeholder = reactive(usePlaceholder())

    function handleSearch(type: string) {
      type === 'table' ? getSearchTableList() : getSearchDBList()
    }

    function clearSearch(type: string) {
      if (type === 'table') {
        state.tableSearchInput = ''
        getSearchTableList()
      }
      else {
        state.DBSearchInput = ''
        getSearchDBList()
      }
    }

    function getSearchTableList() {
      debounce(() => {
        getAllTableList()
      })()
    }

    function getSearchDBList() {
      debounce(() => {
        getAllDatabaseList(true)
      })()
    }

    function handleClickDb(item: IDatabaseItem) {
      if (state.database === item.id) {
        return
      }
      state.database = item.id
      state.tableName = ''
      state.allTableListLoaded.length = 0
      getAllTableList()
    }

    function getPopupContainer(triggerNode: Element) {
      return triggerNode.parentNode
    }

    function clickDatabase() {
    }

    function catalogChange(value: string) {
      state.curCatalog = value
      state.databaseList.length = 0
      state.tableList.length = 0
      state.allDatabaseListLoaded.length = 0
      state.allTableListLoaded.length = 0
      getAllDatabaseList()
    }

    function addDatabase() {
      state.showCreateDBModal = true
    }

    function cancel() {
      state.showCreateDBModal = false
    }
    function createTable() {
      emit('goCreatePage')
    }
    function handleClickTable(item: IMap<string>) {
      state.tableName = item.label
      state.type = item.type
      localStorage.setItem(storageTableKey, JSON.stringify({
        catalog: state.curCatalog,
        database: state.database,
        tableName: item.label,
      }))
      store.updateTablesMenu(false)
      const path = item.type === 'HIVE' ? '/hive-tables' : '/tables'
      const pathQuery = {
        path,
        query: {
          catalog: state.curCatalog,
          db: state.database,
          table: state.tableName,
          type: state.type,
        },
      }
      if (route.path.includes('tables')) {
        router.replace(pathQuery)
        return
      }
      router.push(pathQuery)
    }

    function getCatalogOps() {
      state.catalogLoading = true
      getCatalogList().then((res: ICatalogItem[]) => {
        if (!res) {
          return
        }
        state.catalogOptions = (res || []).map((ele: ICatalogItem) => ({
          value: ele.catalogName,
          label: ele.catalogName,
        }))
        if (state.catalogOptions.length) {
          const index = state.catalogOptions.findIndex(ele => ele.value === storageCataDBTable.catalog)
          const query = route.query
          state.curCatalog = index > -1 ? storageCataDBTable.catalog : (query?.catalog)?.toString() || state.catalogOptions[0].value
        }
        getAllDatabaseList()
      }).finally(() => {
        state.catalogLoading = false
      })
    }

    function getAllDatabaseList(isSearch = false) {
      if (!state.curCatalog) {
        return
      }
      if (state.allDatabaseListLoaded.length) {
        state.databaseList = filteredDatabases.value
        return
      }

      state.loading = true
      getDatabaseList({
        catalog: state.curCatalog,
        keywords: state.DBSearchInput,
      }).then((res: string[]) => {
        state.databaseList = (res || []).map((ele: string) => ({
          id: ele,
          label: ele,
        }))
        if (!isSearch) {
          state.allDatabaseListLoaded = [...state.databaseList]
          if (state.databaseList.length) {
            const index = state.databaseList.findIndex(ele => ele.id === storageCataDBTable.database)
            // ISSUE 2413: If the current catalog is not the one in the query, the first db is selected by default.
            state.database = index > -1 ? storageCataDBTable.database : state.curCatalog === (route.query?.catalog)?.toString() ? ((route.query?.db)?.toString() || state.databaseList[0].id || '') : state.databaseList[0].id || ''
            getAllTableList()
          }
        }
      }).finally(() => {
        state.loading = false
      })
    }

    function getAllTableList() {
      if (!state.curCatalog || !state.database) {
        return
      }
      if (state.allTableListLoaded.length) {
        state.tableList = filteredTables.value
        return
      }

      state.tableLoading = true
      state.tableList.length = 0
      getTableList({
        catalog: state.curCatalog,
        db: state.database,
        keywords: state.tableSearchInput,
      }).then((res: ITableItem[]) => {
        state.tableList = (res || []).map((ele: ITableItem) => ({
          id: ele.name,
          label: ele.name,
          type: ele.type,
        }))
        if (state.tableSearchInput === '') {
          state.allTableListLoaded = [...state.tableList]
        }
      }).finally(() => {
        state.tableLoading = false
      })
    }

    onBeforeMount(() => {
      const { database, tableName } = storageCataDBTable
      state.database = database
      state.tableName = tableName
      getCatalogOps()
    })

    return {
      ...toRefs(state),
      placeholder,
      handleClickDb,
      getPopupContainer,
      clickDatabase,
      catalogChange,
      addDatabase,
      cancel,
      createTable,
      handleClickTable,
      handleSearch,
      clearSearch,
    }
  },
})
</script>

<template>
  <div class="tables-menu">
    <div class="select-catalog g-flex-ac">
      <span class="label">{{ $t('catalog') }}</span>
      <a-select
        v-model:value="curCatalog"
        :options="catalogOptions"
        :loading="catalogLoading"
        :get-popup-container="getPopupContainer"
        class="theme-dark"
        @change="catalogChange"
      />
    </div>
    <div class="tables-wrap g-flex">
      <div class="database-list">
        <div class="list-wrap">
          <div class="add g-flex-jsb">
            <span class="label">{{ $t('database', 2) }}</span>
            <!-- <plus-outlined @click="addDatabase" class="icon" /> -->
          </div>
          <div class="filter-wrap">
            <a-input-search
              v-model:value="DBSearchInput"
              :placeholder="placeholder.filterDBPh"
              class="theme-dark"
              @change="handleSearch('db')"
            >
              <template #prefix>
                <SearchOutlined />
              </template>
              <template v-if="DBSearchInput" #suffix>
                <CloseCircleOutlined class="input-clear-icon" @click="clearSearch('db')" />
              </template>
            </a-input-search>
          </div>
          <u-loading v-if="loading" />
          <VirtualRecycleScroller :loading="loading" :items="databaseList" :active-item="database" :item-size="40" icon-name="database" @handle-click-table="handleClickDb" />
        </div>
      </div>
      <div class="table-list">
        <div class="list-wrap">
          <div class="add g-flex-jsb">
            <span class="label">{{ $t('table', 2) }}</span>
            <!-- <plus-outlined @click="createTable" class="icon" /> -->
          </div>
          <div class="filter-wrap">
            <a-input-search
              v-model:value="tableSearchInput"
              :placeholder="placeholder.filterTablePh"
              class="theme-dark"
              @change="handleSearch('table')"
            >
              <template #prefix>
                <SearchOutlined />
              </template>
              <template v-if="tableSearchInput" #suffix>
                <CloseCircleOutlined class="input-clear-icon" @click="clearSearch('table')" />
              </template>
            </a-input-search>
          </div>
          <u-loading v-if="tableLoading" />
          <VirtualRecycleScroller :loading="tableLoading" :items="tableList" :active-item="tableName" :item-size="40" icon-name="tableOutlined" @handle-click-table="handleClickTable" />
        </div>
      </div>
    </div>
  </div>
  <CreateDBModal :visible="showCreateDBModal" :catalog-options="catalogOptions" @cancel="cancel" />
</template>

<style lang="less" scoped>
  .tables-menu {
    box-sizing: border-box;
    height: 100%;
    width: 512px;
    background-color: @dark-bg-color;
    color: #fff;
    box-shadow: rgb(0 21 41 / 8%) 2px 0 6px;
    .tables-wrap {
      height: calc(100% - 40px);
    }
    .filter-wrap {
      padding: 4px 4px 0;
      .input-clear-icon {
        font-size: 12px;
      }
    }
    :deep(.ant-input-group-addon) {
      display: none;
    }
    .database-list,
    .table-list {
      flex: 1;
      padding-top: 8px;
    }
    .table-list,
    .database-list .list-wrap {
      border-right: 1px solid rgb(255 255 255 / 12%);
    }
    .list-wrap {
      height: calc(100% - 12px);
      position: relative;
      .u-loading {
        background: transparent;
        justify-content: flex-start;
        padding-top: 200px;
      }
    }
    .select-catalog,
    .add {
      align-items: center;
      height: 40px;
      padding: 0 12px;
    }
    .database-list .select-catalog {
      padding-right: 4px;
    }
    .add {
      margin: 4px 4px 0;
      background-color: @dark-bg-primary-color;
    }
    :deep(.select-catalog .ant-select) {
      width: 240px;
      margin-left: 12px;
    }
    .icon {
      cursor: pointer;
    }
    .select-catalog {
      padding-top: 8px;
      border-right: 1px solid rgb(255 255 255 / 12%);
    }
  }
</style>
