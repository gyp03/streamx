<template>
  <div
    class="user-wrapper">
    <div
      class="content-box">
      <a
        href="http://www.streamxhub.com/zh/doc/"
        target="_blank">
        <span
          class="action">
          <a-icon
            style="color:#1890ff"
            type="question-circle-o" />
        </span>
      </a>
      <notice-icon
        style="color:#1890ff"
        class="action" />
      <a-dropdown>
        <span
          style="margin-top: -10px"
          class="action ant-dropdown-link user-dropdown-menu">
          <a-avatar
            class="avatar"
            src="https://avatars.githubusercontent.com/u/13284744?s=180&u=5a0e88e9edeb806f957fc25938522d057f8a2e85&v=4" />
        </span>
        <a-menu
          slot="overlay"
          class="user-dropdown-menu-wrapper">
          <a-menu-item>
            <a
              @click="handleChangePassword">
              <a-icon type="setting" />
              <span>Change password</span>
            </a>
          </a-menu-item>
          <a-menu-item>
            <a
              @click="handleLogout">
              <a-icon type="logout" />
              <span>Sign Out</span>
            </a>
          </a-menu-item>
        </a-menu>
      </a-dropdown>
    </div>

    <a-modal
      v-model="passwordVisible"
      on-ok="handleChangeOk">
      <template
        slot="title">
        <a-icon
          slot="icon"
          type="setting"
          style="color: green"/>
        Change password
      </template>
      <a-form
        @submit="handleChangeOk"
        :form="formPassword">
        <a-form-item
          label="User Name"
          :label-col="{lg: {span: 7}, sm: {span: 7}}"
          :wrapper-col="{lg: {span: 16}, sm: {span: 4} }">
          <a-alert
            :message="userName"
            type="info"/>
        </a-form-item>

        <a-form-item
          :label-col="{lg: {span: 7}, sm: {span: 7}}"
          :wrapper-col="{lg: {span: 16}, sm: {span: 4} }"
          label="Password"
          has-feedback>
          <a-input
            v-decorator="['password',{
              rules: [ { required: true, message: 'Please input your password!' }, { validator: validateToNextPassword}]}]"
            type="password"/>
        </a-form-item>

        <a-form-item
          label="Confirm Password"
          :label-col="{lg: {span: 7}, sm: {span: 7}}"
          :wrapper-col="{lg: {span: 16}, sm: {span: 4} }"
          has-feedback>
          <a-input
            v-decorator="['confirm', {
              rules: [
                { required: true, message: 'Please confirm your password!',},
                { validator: compareToFirstPassword},
              ],
            },
            ]"
            type="password"
            @blur="handleConfirmBlur"/>
        </a-form-item>
      </a-form>

      <template slot="footer">
        <a-button
          key="back"
          @click="handleChangeCancel">
          Cancel
        </a-button>
        <a-button
          key="submit"
          type="primary"
          @click="handleChangeOk">
          Submit
        </a-button>
      </template>
    </a-modal>

  </div>
</template>

<script>
import NoticeIcon from '@/components/NoticeIcon'
import { mapState, mapGetters, mapActions } from 'vuex'
import { password } from '@api/user'

export default {
  name: 'UserMenu',
  components: {
    NoticeIcon
  },

  data() {
    return {
      passwordVisible: false,
      formPassword: null,
      confirmDirty: false,
    }
  },

  computed: {
    ...mapState({
      // 动态主路由
      userName: state => state.user.name
    })
  },

  beforeMount() {
    this.formPassword = this.$form.createForm(this)
  },

  methods: {
    ...mapActions(['SignOut']),
    ...mapGetters(['nickname', 'avatar']),
    handleLogout () {
      const that = this
      this.$confirm({
        content: 'Are you sure Sign Out ?',
        onOk () {
          return that.SignOut({}).then(() => {
            window.location.reload()
          }).catch(err => {
            that.$message.error({
              description: err.message
            })
          })
        },
        onCancel () {
        }
      })
    },

    handleChangePassword () {
      this.passwordVisible = true
    },

    handleChangeOk() {
      this.formPassword.validateFields((err, values) => {
        if (!err) {
          this.handleChangeCancel()
          password({
            username: this.userName,
            password: values.password
          }).then((resp) => {
            this.$swal.fire({
              icon: 'success',
              title: 'password changed successful',
              showConfirmButton: false,
              timer: 2000
            }).then((r)=> {
              this.SignOut({}).then(() => {
                window.location.reload()
              })
            })
          })
        }
      })
    },

    handleChangeCancel () {
        this.passwordVisible = false
        setTimeout(() => {
          this.formPassword.resetFields()
        }, 1000)
    },

    handleConfirmBlur(e) {
      const value = e.target.value
      this.confirmDirty = this.confirmDirty || !!value
    },

    compareToFirstPassword(rule, value, callback) {
      const form = this.formPassword
      if (value && value !== form.getFieldValue('password')) {
        callback('Two passwords that you enter is inconsistent!')
      } else {
        callback()
      }
    },

    validateToNextPassword(rule, value, callback) {
      const form = this.formPassword
      if (value && this.confirmDirty) {
        form.validateFields(['confirm'], { force: true })
      }
      callback()
    },

  }
}
</script>
